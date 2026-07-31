package life.fxs.purr.server.recording

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.application.port.RecordingArchiveWakeup
import life.fxs.purr.server.config.GoogleDriveConfig
import life.fxs.purr.server.repository.CallRecordingRepository
import org.slf4j.LoggerFactory

class RecordingArchiveWorker(
    private val config: GoogleDriveConfig,
    private val repository: CallRecordingRepository,
    private val objectReader: RecordingObjectReader,
    private val uploader: RecordingArchiveUploader?,
    private val nowProvider: () -> Instant = Instant::now,
    private val workerId: String = "recording-archive-${UUID.randomUUID()}",
) : RecordingArchiveWakeup, AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (closed.get() || !config.enabled || uploader == null || !started.compareAndSet(false, true)) return
        job = scope.launch {
            while (isActive && !closed.get()) {
                drain()
                kotlinx.coroutines.withTimeoutOrNull(config.pollIntervalMillis) { wakeups.receive() }
            }
        }
    }

    override fun wake() {
        if (!closed.get()) wakeups.trySend(Unit)
    }

    internal fun uploadOnce(now: Instant = nowProvider()): RecordingArchiveSummary {
        val archive = uploader ?: return RecordingArchiveSummary(0, 0, 0)
        val candidate = repository.claimNextDriveUpload(
            workerId = workerId,
            nowEpochMillis = now.toEpochMilli(),
            leaseUntilEpochMillis = now.plusSeconds(config.leaseSeconds).toEpochMilli(),
        ) ?: return RecordingArchiveSummary(0, 0, 0)
        return try {
            val objectKey = checkNotNull(candidate.objectKey) { "Claimed recording has no local object key" }
            val driveFileId = objectReader.open(objectKey).use { archive.upload(candidate, it) }
            check(driveFileId.isNotBlank()) { "Google Drive returned an empty file ID" }
            check(
                repository.markDriveUploaded(
                    recordingId = candidate.recordingId,
                    workerId = workerId,
                    driveFileId = driveFileId,
                    uploadedAtEpochMillis = now.toEpochMilli(),
                ),
            ) { "Recording archive lease was lost before success could be persisted" }
            RecordingArchiveSummary(1, 1, 0)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val nextAttempt = now.plusSeconds(retryDelaySeconds(candidate.driveUploadAttempts))
            repository.recordDriveUploadFailure(
                recordingId = candidate.recordingId,
                workerId = workerId,
                availableAtEpochMillis = nextAttempt.toEpochMilli(),
                message = error.message ?: error::class.simpleName.orEmpty(),
            )
            logger.warn("Recording {} Google Drive archive failed; retry scheduled", candidate.recordingId, error)
            RecordingArchiveSummary(1, 0, 1)
        }
    }

    private suspend fun drain() {
        while (scope.isActive && !closed.get()) {
            val summary = uploadOnce()
            if (summary.claimed == 0) return
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        runBlocking { job?.join() }
        scope.cancel()
        wakeups.close()
    }

    private fun retryDelaySeconds(attemptCount: Int): Long {
        val shift = (attemptCount - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        return min(config.retryBaseSeconds * (1L shl shift), config.retryMaxSeconds)
    }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 16
    }
}

data class RecordingArchiveSummary(
    val claimed: Int,
    val succeeded: Int,
    val failed: Int,
)
