package life.fxs.purr.server.livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import livekit.LivekitEgress

class LiveKitRecordingOperationReconciliationTest {
    @Test
    fun `durable operation uses a deterministic sanitized output key`() {
        val first = recordingOperationObjectKey("recordings/", "call-1", "command:1")
        val replay = recordingOperationObjectKey("recordings", "call-1", "command:1")

        assertEquals("recordings/call-1/command_1.ogg", first)
        assertEquals(first, replay)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `in-flight egress request is visible before file results exist`() {
        val objectKey = "recordings/call-1/command-1.ogg"
        val request = LivekitEgress.RoomCompositeEgressRequest.newBuilder()
            .setRoomName("room-1")
            .setFile(
                LivekitEgress.EncodedFileOutput.newBuilder()
                    .setFilepath(objectKey)
                    .build(),
            )
            .build()
        val egress = LivekitEgress.EgressInfo.newBuilder()
            .setEgressId("egress-1")
            .setRoomName("room-1")
            .setRoomComposite(request)
            .build()

        assertTrue(egress.targetsObjectKey(objectKey))
    }
}
