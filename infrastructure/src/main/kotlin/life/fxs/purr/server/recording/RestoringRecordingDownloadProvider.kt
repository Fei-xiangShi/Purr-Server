package life.fxs.purr.server.recording

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.RecordingDownloadResult
import life.fxs.purr.server.application.port.RecordingDownloadProvider
import life.fxs.purr.server.application.port.RecordingRecord
import life.fxs.purr.server.config.GoogleDriveConfig
import org.slf4j.LoggerFactory

interface RecordingRestoreStore {
    fun findByRecordingId(recordingId: String): RecordingRecord?

    fun claimRestore(
        recordingId: String,
        workerId: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): RecordingRecord?

    fun markRestored(
        recordingId: String,
        workerId: String,
        objectKey: String,
        restoredAtEpochMillis: Long,
        sizeBytes: Long?,
    ): Boolean

    fun recordRestoreFailure(recordingId: String, workerId: String, message: String): Boolean
}

class RestoringRecordingDownloadProvider(
    private val config: GoogleDriveConfig,
    private val delegate: RecordingDownloadProvider,
    private val repository: RecordingRestoreStore,
    private val objectRestorer: RecordingObjectRestorer,
    private val archiveDownloader: RecordingArchiveDownloader?,
    private val nowProvider: () -> Instant = Instant::now,
    private val sleepProvider: (Long) -> Unit = Thread::sleep,
    private val workerId: String = "recording-restore-${UUID.randomUUID()}",
) : RecordingDownloadProvider, AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(recording: RecordingRecord): Boolean =
        (recording.status == life.fxs.purr.server.model.RecordingStatus.STOPPED ||
            recording.status == life.fxs.purr.server.model.RecordingStatus.DELETED) &&
            (!recording.objectKey.isNullOrBlank() || !recording.driveFileId.isNullOrBlank())

    override fun create(recording: RecordingRecord): RecordingDownloadResult {
        val existingKey = recording.objectKey?.takeIf { it.isNotBlank() }
        if (existingKey != null && objectRestorer.exists(existingKey)) {
            return delegate.create(recording.copy(objectKey = existingKey))
        }

        val driveFileId = recording.driveFileId?.takeIf { it.isNotBlank() }
            ?: throw unavailable(recording.recordingId)
        val restoredKey = restoredObjectKey(recording.recordingId)
        val deadline = nowProvider().toEpochMilli() + config.restoreWaitTimeoutMillis
        while (nowProvider().toEpochMilli() < deadline) {
            val current = repository.findByRecordingId(recording.recordingId)
                ?: throw unavailable(recording.recordingId)
            if (current.objectKey == restoredKey && objectRestorer.exists(restoredKey)) {
                return delegate.create(current.copy(objectKey = restoredKey))
            }
            val now = nowProvider()
            val claimed = repository.claimRestore(
                recordingId = recording.recordingId,
                workerId = workerId,
                nowEpochMillis = now.toEpochMilli(),
                leaseUntilEpochMillis = now.plusSeconds(config.leaseSeconds).toEpochMilli(),
            )
            if (claimed != null) {
                return restoreAndCreate(claimed, driveFileId, restoredKey)
            }
            sleepProvider(config.pollIntervalMillis)
        }
        throw ApplicationException(
            ApplicationError.TEMPORARILY_UNAVAILABLE,
            "Recording restore is still in progress; retry the download",
        )
    }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }

    private fun restoreAndCreate(
        claimed: RecordingRecord,
        driveFileId: String,
        restoredKey: String,
    ): RecordingDownloadResult {
        try {
            if (!objectRestorer.exists(restoredKey)) {
                val downloader = archiveDownloader ?: throw unavailable(claimed.recordingId)
                downloader.download(driveFileId).use { objectRestorer.put(restoredKey, it) }
            }
            check(objectRestorer.exists(restoredKey)) { "Restored recording object is not visible" }
            check(repository.markRestored(
                recordingId = claimed.recordingId,
                workerId = workerId,
                objectKey = restoredKey,
                restoredAtEpochMillis = nowProvider().toEpochMilli(),
                sizeBytes = claimed.sizeBytes,
            )) { "Recording restore lease was lost" }
            val restored = repository.findByRecordingId(claimed.recordingId)
                ?: throw unavailable(claimed.recordingId)
            return delegate.create(restored.copy(objectKey = restoredKey))
        } catch (error: ApplicationException) {
            repository.recordRestoreFailure(claimed.recordingId, workerId, error.message)
            throw error
        } catch (error: Throwable) {
            repository.recordRestoreFailure(
                claimed.recordingId,
                workerId,
                error.message ?: "Recording restore failed",
            )
            logger.warn("Recording {} Google Drive restore failed; retry is available", claimed.recordingId, error)
            throw ApplicationException(
                ApplicationError.EXTERNAL_DEPENDENCY,
                "Recording could not be restored; retry the download",
            )
        }
    }

    private fun unavailable(recordingId: String) = ApplicationException(
        ApplicationError.CONFLICT,
        "Recording is not available for download: $recordingId",
    )

    private fun restoredObjectKey(recordingId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(recordingId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "recordings/restored/$digest.ogg"
    }
}
