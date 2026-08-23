package life.fxs.purr.server.recording

import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.RecordingRecord
import life.fxs.purr.server.config.GoogleDriveConfig
import life.fxs.purr.server.model.RecordingStatus

class RestoringRecordingDownloadProviderTest {
    @Test
    fun `local object uses signed URL without drive read`() {
        val repository = FakeRepository(recording(objectKey = "recordings/call/audio.ogg"))
        val store = FakeRestoreStore(existing = mutableSetOf("recordings/call/audio.ogg"))
        val drive = FakeArchiveDownloader()
        val provider = provider(repository, store, drive)

        provider.create(repository.recording)

        assertEquals(0, drive.downloads)
        assertEquals(0, store.puts)
    }

    @Test
    fun `deleted recording is restored from drive and persisted`() {
        val repository = FakeRepository(recording(objectKey = null))
        val store = FakeRestoreStore()
        val drive = FakeArchiveDownloader()
        val provider = provider(repository, store, drive)

        provider.create(repository.recording)

        assertEquals(1, drive.downloads)
        assertEquals(1, store.puts)
        assertEquals(RecordingStatus.STOPPED, repository.recording.status)
        assertTrue(!repository.recording.objectKey.isNullOrBlank())
    }

    @Test
    fun `missing drive archive returns dependency error without publishing URL`() {
        val repository = FakeRepository(recording(objectKey = null))
        val provider = provider(repository, FakeRestoreStore(), null)

        val error = assertFailsWith<ApplicationException> {
            provider.create(repository.recording)
        }

        assertEquals(ApplicationError.CONFLICT, error.error)
    }

    private fun provider(
        repository: FakeRepository,
        store: FakeRestoreStore,
        drive: FakeArchiveDownloader?,
    ) = RestoringRecordingDownloadProvider(
        config = GoogleDriveConfig(enabled = true, folderId = "folder-1234567890"),
        delegate = FakeDownloadProvider(),
        repository = repository,
        objectRestorer = store,
        archiveDownloader = drive,
        nowProvider = { Instant.ofEpochMilli(1_000) },
        sleepProvider = {},
        workerId = "test-worker",
    )

    private fun recording(objectKey: String?) = RecordingRecord(
        recordingId = "recording-1",
        callId = "call-1",
        status = if (objectKey == null) RecordingStatus.DELETED else RecordingStatus.STOPPED,
        objectKey = objectKey,
        location = null,
        startedAtEpochMillis = 1L,
        endedAtEpochMillis = 2L,
        durationMillis = 1L,
        sizeBytes = AUDIO.size.toLong(),
        errorCode = null,
        errorMessage = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        deletedAtEpochMillis = if (objectKey == null) 3L else null,
        deletionAttempts = 1,
        lastDeletionAttemptAtEpochMillis = null,
        deletionErrorMessage = null,
        driveFileId = "drive-file-1",
    )

    private class FakeRepository(var recording: RecordingRecord) : RecordingRestoreStore {
        override fun findByRecordingId(recordingId: String) = recording
        override fun claimRestore(recordingId: String, workerId: String, nowEpochMillis: Long, leaseUntilEpochMillis: Long) = recording.copy(restoreLeaseOwner = workerId)
        override fun markRestored(recordingId: String, workerId: String, objectKey: String, restoredAtEpochMillis: Long, sizeBytes: Long?) : Boolean { recording = recording.copy(status = RecordingStatus.STOPPED, objectKey = objectKey, deletedAtEpochMillis = null); return true }
        override fun recordRestoreFailure(recordingId: String, workerId: String, message: String) = true
    }

    private class FakeRestoreStore(private val existing: MutableSet<String> = mutableSetOf()) : RecordingObjectRestorer {
        var puts = 0
        override fun exists(objectKey: String) = objectKey in existing
        override fun put(objectKey: String, recordingObject: RecordingObject) { recordingObject.input.readBytes(); existing += objectKey; puts++ }
    }

    private class FakeArchiveDownloader : RecordingArchiveDownloader {
        var downloads = 0
        override fun download(fileId: String): RecordingObject { downloads++; return RecordingObject(ByteArrayInputStream(AUDIO), AUDIO.size.toLong(), "audio/ogg") }
    }

    private class FakeDownloadProvider : life.fxs.purr.server.application.port.RecordingDownloadProvider {
        override fun create(recording: RecordingRecord) = life.fxs.purr.server.application.model.RecordingDownloadResult(recording.recordingId, "http://download", 2_000)
    }

    private companion object { val AUDIO = "audio".toByteArray() }
}
