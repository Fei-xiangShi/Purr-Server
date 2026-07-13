package life.fxs.purr.server.recording

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
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.repository.CallRecordingRepository
import org.slf4j.LoggerFactory

class RecordingRetentionService(
    private val config: RecordingConfig,
    private val repository: CallRecordingRepository,
    private val objectStore: RecordingObjectStore,
    private val nowProvider: () -> Instant = Instant::now,
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
                    cleanupOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("Recording retention pass failed", error)
                }
                delay(config.cleanupIntervalSeconds * MILLIS_PER_SECOND)
            }
        }
    }

    internal fun cleanupOnce(now: Instant = nowProvider()): RecordingCleanupSummary {
        val nowEpochMillis = now.toEpochMilli()
        val candidates = repository.findRetentionCandidates(
            updatedBeforeEpochMillis = now.minusSeconds(config.retentionDays * SECONDS_PER_DAY).toEpochMilli(),
            retryBeforeEpochMillis = nowEpochMillis - config.cleanupIntervalSeconds * MILLIS_PER_SECOND,
            maxAttempts = config.cleanupMaxAttempts,
            limit = config.cleanupBatchSize,
        )
        var claimed = 0
        var deleted = 0
        var failed = 0
        candidates.forEach { candidate ->
            val leased = repository.claimDeletion(candidate, nowEpochMillis, config.cleanupMaxAttempts)
                ?: return@forEach
            claimed++
            val objectKey = leased.objectKey ?: return@forEach
            runCatching { objectStore.delete(objectKey) }
                .onSuccess {
                    repository.markDeleted(leased.recordingId, nowEpochMillis)
                    deleted++
                }
                .onFailure { error ->
                    val message = error.message ?: "Recording object deletion failed"
                    repository.recordDeletionFailure(leased.recordingId, message)
                    failed++
                    if (leased.deletionAttempts >= config.cleanupMaxAttempts) {
                        logger.error("Recording deletion exhausted retries for {}", leased.recordingId, error)
                    } else {
                        logger.warn("Recording deletion failed for {}; it will be retried", leased.recordingId, error)
                    }
                }
        }
        return RecordingCleanupSummary(candidates.size, claimed, deleted, failed)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        runBlocking { job?.join() }
        scope.cancel()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val SECONDS_PER_DAY = 86_400L
    }
}

data class RecordingCleanupSummary(
    val candidates: Int,
    val claimed: Int,
    val deleted: Int,
    val failed: Int,
)
