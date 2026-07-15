package life.fxs.purr.server.recording

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingCommandRecord
import life.fxs.purr.server.application.port.RecordingCommandState
import life.fxs.purr.server.application.port.RecordingCommandStore
import life.fxs.purr.server.application.port.RecordingCommandType
import life.fxs.purr.server.application.port.RecordingCommandWakeup
import life.fxs.purr.server.application.port.RecordingCommandProcessor
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.config.OutboxConfig
import life.fxs.purr.server.model.CallDurationPolicy
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import org.slf4j.LoggerFactory

/**
 * Executes durable recording commands outside request/webhook transactions.
 * Claims are leased in the database, provider calls carry the command id as
 * their idempotency key, and the provider result is committed before the
 * command lease is released.
 */
class RecordingCommandDispatcher(
    private val config: OutboxConfig,
    private val repository: RecordingCommandStore,
    private val callSessionStore: CallSessionStore,
    private val recordingController: RecordingController,
    private val nowProvider: () -> Instant = Instant::now,
    private val workerId: String = "recording-command-${UUID.randomUUID()}",
) : AutoCloseable, RecordingCommandWakeup, RecordingCommandProcessor {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val wakeRequested = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        check(!closed.get()) { "Recording command dispatcher is closed" }
        check(started.compareAndSet(false, true)) { "Recording command dispatcher is already started" }
        job = scope.launch {
            while (isActive && !closed.get()) {
                try {
                    dispatchOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("Recording command dispatch pass failed", error)
                }
                if (!wakeRequested.getAndSet(false)) {
                    delay(config.pollIntervalMillis)
                }
            }
        }
    }

    override fun wake() {
        if (started.get() && !closed.get()) wakeRequested.set(true)
    }

    override fun processPending() {
        if (!started.get() || closed.get()) return
        dispatchOnce()
    }

    internal fun dispatchOnce(now: Instant = nowProvider()): RecordingCommandDispatchSummary {
        val nowEpochMillis = now.toEpochMilli()
        val reconciled = runCatching { repository.reconcileOpenCalls(nowEpochMillis) }
            .onFailure { logger.warn("Recording command reconciliation failed", it) }
            .getOrDefault(0)
        val leaseUntil = now.plusSeconds(config.leaseSeconds).toEpochMilli()
        val records = repository.claimBatch(
            workerId = workerId,
            nowEpochMillis = nowEpochMillis,
            leaseUntilEpochMillis = leaseUntil,
            maxAttempts = config.maxAttempts,
            limit = config.batchSize,
        )
        var succeeded = 0
        var failed = 0
        records.forEach { command ->
            val outcome = runCatching { execute(command, nowEpochMillis) }
            outcome.onSuccess {
                if (repository.markSucceeded(
                        commandId = command.commandId,
                        workerId = workerId,
                        result = it,
                        completedAtEpochMillis = nowProvider().toEpochMilli(),
                    )
                ) {
                    succeeded++
                }
            }.onFailure { error ->
                val failureCount = command.attemptCount + 1
                val terminal = failureCount >= config.maxAttempts
                val retryAt = now.plusSeconds(retryDelaySeconds(failureCount)).toEpochMilli()
                if (repository.markFailed(
                        commandId = command.commandId,
                        workerId = workerId,
                        availableAtEpochMillis = retryAt,
                        errorMessage = error.message ?: error::class.simpleName ?: "Recording command failed",
                        terminal = terminal,
                        completedAtEpochMillis = nowEpochMillis,
                    )
                ) {
                    failed++
                }
                if (terminal) {
                    logger.error("Recording command {} exhausted retries", command.commandId, error)
                } else {
                    logger.warn("Recording command {} failed; retry scheduled", command.commandId, error)
                }
            }
        }
        return RecordingCommandDispatchSummary(
            reconciled = reconciled,
            claimed = records.size,
            succeeded = succeeded,
            failed = failed,
        )
    }

    private fun execute(command: RecordingCommandRecord, nowEpochMillis: Long): ProviderRecordingResult {
        val call = callSessionStore.find(command.callId)
            ?: error("Call ${command.callId} no longer exists")
        val preserveProviderClock = command.type == RecordingCommandType.START &&
            call.state == CallState.ENDED &&
            call.recordingStatus in setOf(RecordingStatus.STARTING, RecordingStatus.STOPPING)
        val result = when (command.type) {
            RecordingCommandType.START -> executeStart(command, call, nowEpochMillis)
            RecordingCommandType.STOP -> {
                val recordingId = command.recordingId ?: call.recordingId
                if (recordingId.isNullOrBlank()) {
                    if (call.state == CallState.ENDED &&
                        call.recordingStatus == RecordingStatus.STOPPED
                    ) {
                        return ProviderRecordingResult(
                            status = RecordingStatus.STOPPED,
                            recordingId = null,
                            updatedAtEpochMillis = nowEpochMillis,
                        )
                    }
                    // A missing id is never proof that provider START did not
                    // happen. Keep retrying instead of acknowledging a STOP
                    // that did not reach the provider.
                    error("Recording id is not available for stop command ${command.commandId}")
                }
                recordingController.stopRecording(
                    callId = command.callId,
                    roomName = command.roomName,
                    currentRecordingId = recordingId,
                    operationId = command.commandId,
                )
            }
        }
        return result.copy(
            recordingId = result.recordingId ?: command.recordingId,
            updatedAtEpochMillis = if (preserveProviderClock) {
                result.updatedAtEpochMillis
            } else {
                maxOf(result.updatedAtEpochMillis, nowEpochMillis)
            },
        )
    }

    private fun executeStart(
        command: RecordingCommandRecord,
        initialCall: CallRecord,
        nowEpochMillis: Long,
    ): ProviderRecordingResult {
        if (initialCall.state == CallState.ENDED) {
            if (initialCall.recordingStatus in setOf(RecordingStatus.STARTING, RecordingStatus.STOPPING)) {
                // The process may have died after the provider accepted START
                // but before its id was committed. Reconcile only states that
                // prove provider I/O may already have happened.
                recordingController.findRecordingForOperation(
                    callId = command.callId,
                    roomName = command.roomName,
                    operationId = command.commandId,
                )?.let { return it }
                return ProviderRecordingResult(
                    status = RecordingStatus.STOPPED,
                    recordingId = initialCall.recordingId,
                    updatedAtEpochMillis = nowEpochMillis,
                )
            }
            // A delayed START for a call that ended while still IDLE is a
            // successful no-op. In particular, no provider request is made.
            return initialCall.toProviderRecordingResult(nowEpochMillis)
        }
        check(initialCall.state == CallState.ACTIVE) {
            "Cannot start recording for call ${command.callId} in state ${initialCall.state.wireValue}"
        }

        val call = when (initialCall.recordingStatus) {
            RecordingStatus.IDLE,
            RecordingStatus.STOPPED,
            RecordingStatus.FAILED,
            RecordingStatus.DELETED,
            -> callSessionStore.claimRecordingStartAfterMinimumDuration(
                callId = command.callId,
                updatedAtEpochMillis = nowEpochMillis,
                minimumConnectedDurationMillis = CallDurationPolicy.MINIMUM_RECORDING_DURATION_MILLIS,
            ) ?: callSessionStore.find(command.callId)
                ?: error("Call ${command.callId} no longer exists")
            RecordingStatus.STARTING,
            RecordingStatus.STOPPING,
            RecordingStatus.RECORDING,
            -> initialCall
        }

        if (call.state != CallState.ACTIVE) return call.toProviderRecordingResult(nowEpochMillis)
        if (call.recordingStatus == RecordingStatus.RECORDING) {
            return call.toProviderRecordingResult(nowEpochMillis)
        }
        check(call.recordingStatus in setOf(RecordingStatus.STARTING, RecordingStatus.STOPPING)) {
            "Call ${command.callId} is not eligible to start recording"
        }
        return recordingController.startRecording(
            callId = command.callId,
            roomName = command.roomName,
            operationId = command.commandId,
        )
    }

    private fun CallRecord.toProviderRecordingResult(
        nowEpochMillis: Long,
    ) = ProviderRecordingResult(
        status = recordingStatus,
        recordingId = recordingId,
        updatedAtEpochMillis = nowEpochMillis,
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        runBlocking { job?.join() }
        scope.cancel()
    }

    private fun retryDelaySeconds(attemptCount: Int): Long {
        val multiplier = 1L shl min((attemptCount - 1).coerceAtLeast(0), MAX_BACKOFF_SHIFT)
        return min(config.retryBaseSeconds * multiplier, config.retryMaxSeconds)
    }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 20
    }
}

data class RecordingCommandDispatchSummary(
    val reconciled: Int,
    val claimed: Int,
    val succeeded: Int,
    val failed: Int,
)
