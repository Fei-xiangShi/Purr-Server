package life.fxs.purr.server.recording

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
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
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.repository.CallRecordingRepository
import org.slf4j.LoggerFactory

class RecordingRetentionService(
    private val config: RecordingConfig,
    private val repository: CallRecordingRepository,
    private val objectStore: RecordingObjectStore,
    private val nowProvider: () -> Instant = Instant::now,
    private val workerId: String = "recording-retention-${UUID.randomUUID()}",
    private val delayProvider: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (closed.get() || !config.cleanupEnabled || !started.compareAndSet(false, true)) return
        job = scope.launch {
            while (isActive && !closed.get()) {
                try {
                    delayProvider(millisUntilNextRun(nowProvider()))
                    cleanupScheduledPass()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("Recording retention pass failed", error)
                }
            }
        }
    }

    internal fun nextCleanupInstant(now: Instant): Instant {
        val zone = ZoneId.of(config.cleanupTimeZone)
        val localNow = now.atZone(zone)
        val cleanupTime = LocalTime.of(config.cleanupHour, config.cleanupMinute)
        var next = localNow.toLocalDate().atTime(cleanupTime).atZone(zone).toInstant()
        if (!next.isAfter(now)) {
            next = localNow.toLocalDate().plusDays(1).atTime(cleanupTime).atZone(zone).toInstant()
        }
        return next
    }

    internal fun millisUntilNextRun(now: Instant): Long =
        (nextCleanupInstant(now).toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1L)

    internal fun cleanupOnce(now: Instant = nowProvider()): RecordingCleanupSummary {
        val nowEpochMillis = now.toEpochMilli()
        val candidates = repository.findRetentionCandidates(
            endedBeforeEpochMillis = now.minusSeconds(config.retentionDays * SECONDS_PER_DAY).toEpochMilli(),
            nowEpochMillis = nowEpochMillis,
            limit = config.cleanupBatchSize,
        )
        var claimed = 0
        var deleted = 0
        var failed = 0
        candidates.forEach { candidate ->
            val leased = repository.claimDeletion(
                candidate = candidate,
                workerId = workerId,
                attemptedAtEpochMillis = nowEpochMillis,
                leaseUntilEpochMillis = now.plusSeconds(config.cleanupLeaseSeconds).toEpochMilli(),
            )
                ?: return@forEach
            claimed++
            runCatching {
                objectStore.delete(checkNotNull(leased.objectKey) { "Claimed recording has no local object key" })
            }
                .onSuccess {
                    if (repository.markDeleted(leased.recordingId, workerId, nowEpochMillis)) deleted++
                }
                .onFailure { error ->
                    val message = error.message ?: "Recording object deletion failed"
                    repository.recordDeletionFailure(leased.recordingId, workerId, message)
                    failed++
                    logger.warn("Recording deletion failed for {}; it will be retried", leased.recordingId, error)
                }
        }
        return RecordingCleanupSummary(candidates.size, claimed, deleted, failed)
    }

    internal fun cleanupScheduledPass(now: Instant = nowProvider()): RecordingCleanupSummary {
        var total = RecordingCleanupSummary(0, 0, 0, 0)
        var batch: RecordingCleanupSummary
        do {
            batch = cleanupOnce(now)
            total = RecordingCleanupSummary(
                candidates = total.candidates + batch.candidates,
                claimed = total.claimed + batch.claimed,
                deleted = total.deleted + batch.deleted,
                failed = total.failed + batch.failed,
            )
        } while (batch.candidates == config.cleanupBatchSize && batch.claimed > 0)
        return total
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        runBlocking { job?.join() }
        scope.cancel()
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}

data class RecordingCleanupSummary(
    val candidates: Int,
    val claimed: Int,
    val deleted: Int,
    val failed: Int,
)
