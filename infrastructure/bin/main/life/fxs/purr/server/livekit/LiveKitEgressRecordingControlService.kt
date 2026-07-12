package life.fxs.purr.server.livekit

import io.livekit.server.AudioMixing
import io.livekit.server.EgressServiceClient
import io.livekit.server.RoomServiceClient
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.config.LiveKitConfig
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.model.RecordingStatus
import livekit.LivekitEgress
import retrofit2.Call

class LiveKitEgressRecordingControlService(
    private val liveKitConfig: LiveKitConfig,
    private val recordingConfig: RecordingConfig,
    private val nowProvider: () -> Instant = Instant::now,
) : RecordingController {
    private val client: EgressServiceClient by lazy {
        EgressServiceClient.createClient(
            liveKitConfig.httpUrl,
            liveKitConfig.apiKey,
            liveKitConfig.apiSecret,
        )
    }
    private val roomClient: RoomServiceClient by lazy {
        RoomServiceClient.createClient(
            liveKitConfig.httpUrl,
            liveKitConfig.apiKey,
            liveKitConfig.apiSecret,
        )
    }

    override fun startRecording(callId: String, roomName: String): ProviderRecordingResult {
        ensureEnabled()
        ensureRoomExists(roomName)
        val now = nowProvider()
        val objectKey = buildObjectKey(callId, now)
        val response = client.startRoomCompositeEgress(
            roomName,
            createFileOutput(objectKey),
            EMPTY_LAYOUT,
            null,
            null,
            true,
            false,
            EMPTY_CUSTOM_BASE_URL,
            AudioMixing.DUAL_CHANNEL_ALTERNATE,
        ).executeOrThrow("start recording")
        return response.toRecordingResult(
            knownObjectKey = objectKey,
        )
    }

    override fun stopRecording(
        callId: String,
        roomName: String,
        currentRecordingId: String?,
    ): ProviderRecordingResult {
        ensureEnabled()
        val recordingId = currentRecordingId
            ?: throw ApplicationException(
                ApplicationError.CONFLICT,
                "Recording is not active for call: $callId",
            )
        val response = client.stopEgress(recordingId)
            .executeOrThrow("stop recording")
        return response.toRecordingResult().let { result ->
            if (result.recordingId == null) result.copy(recordingId = recordingId) else result
        }
    }

    override fun getRecording(recordingId: String): ProviderRecordingResult? {
        ensureEnabled()
        return client.listEgress("", recordingId, null)
            .executeOrThrow("get recording status")
            .firstOrNull { it.egressId == recordingId }
            ?.toRecordingResult()
    }

    private fun ensureRoomExists(roomName: String) {
        val rooms = roomClient.listRooms(listOf(roomName)).executeOrThrow("list rooms")
        if (rooms.any { it.name == roomName }) {
            return
        }
        roomClient.createRoom(roomName).executeOrThrow("create room")
    }

    private fun createFileOutput(objectKey: String): LivekitEgress.EncodedFileOutput {
        val s3Upload = LivekitEgress.S3Upload.newBuilder()
            .setAccessKey(recordingConfig.accessKey)
            .setSecret(recordingConfig.secretKey)
            .setRegion(recordingConfig.region)
            .setEndpoint(recordingConfig.endpoint)
            .setBucket(recordingConfig.bucket)
            .setForcePathStyle(recordingConfig.forcePathStyle)
            .build()

        return LivekitEgress.EncodedFileOutput.newBuilder()
            .setFileType(LivekitEgress.EncodedFileType.OGG)
            .setFilepath(objectKey)
            .setS3(s3Upload)
            .build()
    }

    private fun buildObjectKey(callId: String, now: Instant): String {
        val timestamp = timestampFormatter.format(now)
        return "${recordingConfig.filePrefix.trimEnd('/')}/$callId/$timestamp.ogg"
    }

    private fun ensureEnabled() {
        if (!recordingConfig.enabled) {
            error("LiveKit recording controller cannot be used while recording is disabled")
        }
        if (recordingConfig.provider != life.fxs.purr.server.config.RecordingProvider.LIVEKIT) {
            error("LiveKit recording controller requires the livekit provider")
        }
    }

    private fun <T> Call<T>.executeOrThrow(action: String): T {
        val response = execute()
        if (!response.isSuccessful) {
            throw ApplicationException(
                ApplicationError.EXTERNAL_DEPENDENCY,
                "LiveKit failed to $action: ${response.code()} ${response.message()}",
            )
        }
        return response.body()
            ?: throw ApplicationException(
                ApplicationError.EXTERNAL_DEPENDENCY,
                "LiveKit returned empty response while trying to $action",
            )
    }

    private companion object {
        const val EMPTY_LAYOUT = ""
        const val EMPTY_CUSTOM_BASE_URL = ""
        val timestampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
    }
}
