package life.fxs.purr.server.recording

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import life.fxs.purr.server.application.port.RecordingRecord
import life.fxs.purr.server.config.GoogleDriveConfig
import life.fxs.purr.server.model.RecordingStatus

class GoogleDriveRecordingArchiveTest {
    @Test
    fun `existing recording property is reused without creating a duplicate`() {
        val gateway = FakeDriveGateway(existingFileId = "existing-drive-file")
        val archive = GoogleDriveRecordingArchive(config(), gateway)

        val fileId = recordingObject().use { archive.upload(recording(), it) }

        assertEquals("existing-drive-file", fileId)
        assertEquals(1, gateway.findCalls)
        assertEquals(0, gateway.createCalls)
        archive.close()
    }

    @Test
    fun `new Drive file receives recording identity and original object name`() {
        val gateway = FakeDriveGateway(existingFileId = null)
        val archive = GoogleDriveRecordingArchive(config(), gateway)

        val fileId = recordingObject().use { archive.upload(recording(), it) }

        assertEquals("created-drive-file", fileId)
        assertEquals(1, gateway.findCalls)
        assertEquals(1, gateway.createCalls)
        assertEquals("folder-1234567890", gateway.createdFolderId)
        assertEquals("recording-1", gateway.createdRecordingId)
        assertEquals("audio.ogg", gateway.createdFileName)
        assertTrue(gateway.createdBytes.contentEquals(AUDIO))
        archive.close()
    }

    private fun config() = GoogleDriveConfig(
        enabled = true,
        serviceAccountPath = "/unused/test.json",
        folderId = "folder-1234567890",
    )

    private fun recording() = RecordingRecord(
        recordingId = "recording-1",
        callId = "call-1",
        status = RecordingStatus.STOPPED,
        objectKey = "recordings/call-1/audio.ogg",
        location = null,
        startedAtEpochMillis = 1L,
        endedAtEpochMillis = 2L,
        durationMillis = 1L,
        sizeBytes = AUDIO.size.toLong(),
        errorCode = null,
        errorMessage = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        deletedAtEpochMillis = null,
        deletionAttempts = 0,
        lastDeletionAttemptAtEpochMillis = null,
        deletionErrorMessage = null,
    )

    private fun recordingObject() = RecordingObject(
        input = ByteArrayInputStream(AUDIO),
        contentLength = AUDIO.size.toLong(),
        contentType = "audio/ogg",
    )

    private class FakeDriveGateway(
        private val existingFileId: String?,
    ) : GoogleDriveGateway {
        var findCalls = 0
        var createCalls = 0
        var createdFolderId: String? = null
        var createdRecordingId: String? = null
        var createdFileName: String? = null
        var createdBytes = byteArrayOf()

        override fun find(folderId: String, recordingId: String): String? {
            findCalls++
            return existingFileId
        }

        override fun create(
            folderId: String,
            recordingId: String,
            fileName: String,
            recordingObject: RecordingObject,
        ): String {
            createCalls++
            createdFolderId = folderId
            createdRecordingId = recordingId
            createdFileName = fileName
            createdBytes = recordingObject.input.readBytes()
            return "created-drive-file"
        }
    }

    private companion object {
        val AUDIO = "audio-bytes".toByteArray()
    }
}
