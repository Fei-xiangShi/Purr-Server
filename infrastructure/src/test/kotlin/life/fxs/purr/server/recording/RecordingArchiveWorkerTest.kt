package life.fxs.purr.server.recording

import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.config.GoogleDriveConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository

class RecordingArchiveWorkerTest {
    @Test
    fun `completed recording is streamed and Drive identity is persisted`() = withDatabase {
        val repository = seedCompletedRecording()
        var openedKey: String? = null
        val worker = RecordingArchiveWorker(
            config = driveConfig(),
            repository = repository,
            objectReader = RecordingObjectReader { key ->
                openedKey = key
                RecordingObject(ByteArrayInputStream(AUDIO), AUDIO.size.toLong(), "audio/ogg")
            },
            uploader = RecordingArchiveUploader { recording, recordingObject ->
                assertEquals(RECORDING_ID, recording.recordingId)
                assertTrue(recordingObject.input.readBytes().contentEquals(AUDIO))
                "drive-file-1"
            },
            workerId = "worker-a",
        )

        assertEquals(RecordingArchiveSummary(1, 1, 0), worker.uploadOnce(NOW))
        assertEquals(RecordingArchiveSummary(0, 0, 0), worker.uploadOnce(NOW))

        val stored = assertNotNull(repository.findByRecordingId(RECORDING_ID))
        assertEquals(OBJECT_KEY, openedKey)
        assertEquals("drive-file-1", stored.driveFileId)
        assertEquals(NOW.toEpochMilli(), stored.driveUploadedAtEpochMillis)
        assertNotNull(stored.objectKey)
        assertNull(stored.driveUploadErrorMessage)
        worker.close()
    }

    @Test
    fun `failed upload backs off and succeeds on a later retry`() = withDatabase {
        val repository = seedCompletedRecording()
        var uploadCalls = 0
        val worker = RecordingArchiveWorker(
            config = driveConfig(),
            repository = repository,
            objectReader = RecordingObjectReader {
                RecordingObject(ByteArrayInputStream(AUDIO), AUDIO.size.toLong(), "audio/ogg")
            },
            uploader = RecordingArchiveUploader { _, _ ->
                uploadCalls++
                if (uploadCalls == 1) error("Drive unavailable")
                "drive-file-retry"
            },
            workerId = "worker-a",
        )

        assertEquals(RecordingArchiveSummary(1, 0, 1), worker.uploadOnce(NOW))
        assertEquals(RecordingArchiveSummary(0, 0, 0), worker.uploadOnce(NOW.plusSeconds(4)))
        assertEquals(RecordingArchiveSummary(1, 1, 0), worker.uploadOnce(NOW.plusSeconds(5)))

        val stored = assertNotNull(repository.findByRecordingId(RECORDING_ID))
        assertEquals(2, stored.driveUploadAttempts)
        assertEquals("drive-file-retry", stored.driveFileId)
        assertNull(stored.driveUploadErrorMessage)
        assertNotNull(stored.objectKey)
        worker.close()
    }

    @Test
    fun `upload lease prevents concurrent work and can be reclaimed after expiry`() = withDatabase {
        val repository = seedCompletedRecording()

        val first = repository.claimNextDriveUpload("worker-a", 1_000L, 2_000L)
        val concurrent = repository.claimNextDriveUpload("worker-b", 1_001L, 2_001L)
        val recovered = repository.claimNextDriveUpload("worker-b", 2_001L, 3_001L)

        assertNotNull(first)
        assertNull(concurrent)
        assertNotNull(recovered)
        assertEquals("worker-b", recovered.driveUploadLeaseOwner)
        assertEquals(2, recovered.driveUploadAttempts)
    }

    private fun seedCompletedRecording(): CallRecordingRepository {
        val users = UserRepository()
        users.insertIfAbsent("user-a", "user-a", "pass-a", "A", null)
        users.insertIfAbsent("user-b", "user-b", "pass-b", "B", null)
        PairBondRepository().insertIfAbsent("pair-1", "user-a", "user-b", 1L)
        CallSessionRepository().upsert(
            CallRecord(
                callId = CALL_ID,
                pairId = "pair-1",
                roomName = "room-1",
                createdByUserId = "user-a",
                startedAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                endedAtEpochMillis = 2L,
                state = CallState.ENDED,
                recordingStatus = RecordingStatus.STOPPED,
                recordingId = RECORDING_ID,
            ),
        )
        return CallRecordingRepository().also { repository ->
            repository.updateCurrent(
                CALL_ID,
                ProviderRecordingResult(
                    status = RecordingStatus.STOPPED,
                    recordingId = RECORDING_ID,
                    updatedAtEpochMillis = 2L,
                    objectKey = OBJECT_KEY,
                    endedAtEpochMillis = 2L,
                    sizeBytes = AUDIO.size.toLong(),
                ),
            )
        }
    }

    private fun driveConfig() = GoogleDriveConfig(
        enabled = true,
        oauthCredentialPath = "/unused/test.json",
        folderId = "drive-folder-123456",
        pollIntervalMillis = 1_000,
        leaseSeconds = 60,
        retryBaseSeconds = 5,
        retryMaxSeconds = 60,
    )

    private fun withDatabase(block: () -> Unit) {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:recording-archive-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()
        try {
            block()
        } finally {
            resources.close()
        }
    }

    private companion object {
        const val CALL_ID = "call-1"
        const val RECORDING_ID = "recording-1"
        const val OBJECT_KEY = "recordings/call-1/audio.ogg"
        val AUDIO = "audio-bytes".toByteArray()
        val NOW: Instant = Instant.parse("2026-07-31T10:00:00Z")
    }
}
