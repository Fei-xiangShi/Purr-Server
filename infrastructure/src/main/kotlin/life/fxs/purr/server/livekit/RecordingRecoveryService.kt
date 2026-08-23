package life.fxs.purr.server.livekit

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
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
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallSessionRepository
import org.slf4j.LoggerFactory

class RecordingRecoveryService(
    private val config: RecordingConfig,
    private val callSessionRepository: CallSessionRepository,
    private val callRecordingRepository: CallRecordingRepository,
    private val recordingController: RecordingController,
    private val nowProvider: () -> Instant = Instant::now,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (closed.get() || !config.recoveryEnabled || !started.compareAndSet(false, true)) return
        job = scope.launch {
            while (isActive && !closed.get()) {
                try {
                    recoverOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("Recording recovery pass failed", error)
                }
                delay(config.recoveryIntervalSeconds * MILLIS_PER_SECOND)
            }
        }
    }

    internal fun recoverOnce(now: Instant = nowProvider()): RecordingRecoverySummary {
        val nowEpochMillis = now.toEpochMilli()
        val candidates = callSessionRepository.findRecordingRecoveryCandidates(
            staleBeforeEpochMillis = nowEpochMillis - config.recoveryStaleAfterSeconds * MILLIS_PER_SECOND,
            retryBeforeEpochMillis = nowEpochMillis - config.recoveryIntervalSeconds * MILLIS_PER_SECOND,
            maxAttempts = config.recoveryMaxAttempts,
        )
        var claimed = 0
        var recovered = 0
        var terminalFailures = 0
        candidates.forEach { candidate ->
            val leased = callSessionRepository.claimRecordingRecovery(
                candidate = candidate,
                claimedAtEpochMillis = nowEpochMillis,
                maxAttempts = config.recoveryMaxAttempts,
            ) ?: return@forEach
            claimed++
            runCatching { recover(leased, nowEpochMillis) }
                .onSuccess { outcome ->
                    when (outcome) {
                        RecoveryOutcome.RECOVERED -> recovered++
                        RecoveryOutcome.RETRY_PENDING -> Unit
                        RecoveryOutcome.TERMINAL_FAILURE -> terminalFailures++
                    }
                }
                .onFailure { error ->
                    val terminal = leased.recordingRecoveryAttempts >= config.recoveryMaxAttempts
                    persistFailure(leased, error.message ?: "Recording recovery failed", nowEpochMillis, terminal)
                    if (terminal) terminalFailures++
                    logger.warn("Failed to recover recording for callId={}", leased.callId, error)
                }
        }
        return RecordingRecoverySummary(candidates.size, claimed, recovered, terminalFailures)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        runBlocking { job?.join() }
        scope.cancel()
    }

    private fun recover(call: CallRecord, nowEpochMillis: Long): RecoveryOutcome {
        val recordingId = call.recordingId
        if (recordingId == null) {
            persistFailure(call, "Recording provider id is missing", nowEpochMillis, terminal = true)
            return RecoveryOutcome.TERMINAL_FAILURE
        }

        val providerState = recordingController.getRecording(recordingId)
        if (providerState == null) {
            val terminal = call.recordingRecoveryAttempts >= config.recoveryMaxAttempts
            persistFailure(call, "Recording was not found in LiveKit", nowEpochMillis, terminal)
            return if (terminal) RecoveryOutcome.TERMINAL_FAILURE else RecoveryOutcome.RETRY_PENDING
        }

        if (call.state == CallState.ENDED &&
            providerState.status in setOf(RecordingStatus.STARTING, RecordingStatus.RECORDING)
        ) {
            val stopped = recordingController.stopRecording(
                callId = call.callId,
                roomName = call.roomName,
                currentRecordingId = recordingId,
                operationId = "recovery-stop:${call.callId}:${call.recordingRecoveryAttempts}",
            )
            callRecordingRepository.updateCurrent(call.callId, stopped)
            return RecoveryOutcome.RECOVERED
        }

        if (providerState.status in setOf(RecordingStatus.STARTING, RecordingStatus.STOPPING)) {
            val terminal = call.recordingRecoveryAttempts >= config.recoveryMaxAttempts
            persistFailure(
                call,
                "Recording remained ${providerState.status.wireValue} after recovery check",
                nowEpochMillis,
                terminal,
            )
            return if (terminal) RecoveryOutcome.TERMINAL_FAILURE else RecoveryOutcome.RETRY_PENDING
        }

        callRecordingRepository.updateCurrent(call.callId, providerState)
        return RecoveryOutcome.RECOVERED
    }

    private fun persistFailure(
        call: CallRecord,
        message: String,
        nowEpochMillis: Long,
        terminal: Boolean,
    ) {
        if (terminal) {
            callRecordingRepository.updateCurrent(
                call.callId,
                ProviderRecordingResult(
                    status = RecordingStatus.FAILED,
                    recordingId = call.recordingId,
                    updatedAtEpochMillis = nowEpochMillis,
                    errorMessage = message.take(MAX_ERROR_LENGTH),
                ),
            )
        } else {
            callSessionRepository.recordRecoveryFailure(
                callId = call.callId,
                message = message,
                failedAtEpochMillis = nowEpochMillis,
                terminal = false,
            )
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_ERROR_LENGTH = 2_048
    }

    private enum class RecoveryOutcome {
        RECOVERED,
        RETRY_PENDING,
        TERMINAL_FAILURE,
    }
}

data class RecordingRecoverySummary(
    val candidates: Int,
    val claimed: Int,
    val recovered: Int,
    val terminalFailures: Int,
)
