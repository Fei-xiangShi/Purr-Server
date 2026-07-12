package life.fxs.purr.server.livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import life.fxs.purr.server.model.RecordingStatus
import livekit.LivekitEgress

class LiveKitRecordingMapperTest {
    @Test
    fun `completed egress maps file metadata and nanoseconds to milliseconds`() {
        val fileInfo = LivekitEgress.FileInfo.newBuilder()
            .setFilename("recordings/call-1/audio.ogg")
            .setLocation("s3://purr-recordings/recordings/call-1/audio.ogg")
            .setStartedAt(1_000_000_000L)
            .setEndedAt(4_000_000_000L)
            .setDuration(3_000_000_000L)
            .setSize(42_000L)
            .build()
        val egress = LivekitEgress.EgressInfo.newBuilder()
            .setEgressId("egress-1")
            .setStatus(LivekitEgress.EgressStatus.EGRESS_COMPLETE)
            .setUpdatedAt(5_000_000_000L)
            .addFileResults(fileInfo)
            .build()

        val result = egress.toRecordingResult()

        assertEquals(RecordingStatus.STOPPED, result.status)
        assertEquals("egress-1", result.recordingId)
        assertEquals("recordings/call-1/audio.ogg", result.objectKey)
        assertEquals("s3://purr-recordings/recordings/call-1/audio.ogg", result.location)
        assertEquals(1_000L, result.startedAtEpochMillis)
        assertEquals(4_000L, result.endedAtEpochMillis)
        assertEquals(3_000L, result.durationMillis)
        assertEquals(42_000L, result.sizeBytes)
        assertEquals(5_000L, result.updatedAtEpochMillis)
        assertNull(result.errorMessage)
    }

    @Test
    fun `failed egress maps provider error`() {
        val egress = LivekitEgress.EgressInfo.newBuilder()
            .setEgressId("egress-2")
            .setStatus(LivekitEgress.EgressStatus.EGRESS_FAILED)
            .setErrorCode(503)
            .setError("storage unavailable")
            .setUpdatedAt(10_000_000L)
            .build()

        val result = egress.toRecordingResult()

        assertEquals(RecordingStatus.FAILED, result.status)
        assertEquals(503, result.errorCode)
        assertEquals("storage unavailable", result.errorMessage)
        assertEquals(10L, result.updatedAtEpochMillis)
    }
}
