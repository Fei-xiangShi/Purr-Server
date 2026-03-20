package life.fxs.purr.server.livekit

import io.ktor.http.HttpStatusCode
import io.livekit.server.AudioMixing
import io.livekit.server.EgressServiceClient
import io.livekit.server.RoomServiceClient
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.config.LiveKitConfig
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.model.RecordingStatus
import livekit.LivekitEgress
import retrofit2.Call

class LiveKitEgressRecordingControlService(
    private val liveKitConfig: LiveKitConfig,
    private val recordingConfig: RecordingConfig,
    private val nowProvider: () -> Instant = Instant::now,
) : RecordingControlService {
    private val client: EgressServiceClient by lazy {
        EgressServiceClient.create(
            liveKitConfig.httpUrl,
            liveKitConfig.apiKey,
            liveKitConfig.apiSecret,
        )
    }
    private val roomClient: RoomServiceClient by lazy {
        RoomServiceClient.create(
            liveKitConfig.httpUrl,
            liveKitConfig.apiKey,
            liveKitConfig.apiSecret,
        )
    }

    override fun startRecording(callId: String, roomName: String): RecordingResult {
        ensureEnabled()
        ensureRoomExists(roomName)
        val now = nowProvider()
        val response = client.startRoomCompositeEgress(
            roomName,
            createFileOutput(callId, now),
            EMPTY_LAYOUT,
            null,
            null,
            true,
            false,
            EMPTY_CUSTOM_BASE_URL,
            AudioMixing.DUAL_CHANNEL_ALTERNATE,
        ).executeOrThrow("start recording")
        return RecordingResult(
            status = response.status.toRecordingStatus(),
            recordingId = response.egressId,
            updatedAtEpochMillis = now.toEpochMilli(),
        )
    }

    override fun stopRecording(callId: String, roomName: String, currentRecordingId: String?): RecordingResult {
        ensureEnabled()
        val recordingId = currentRecordingId
            ?: throw ApiException(HttpStatusCode.BadRequest, "Recording is not active for call: $callId")
        val now = nowProvider()
        val response = client.stopEgress(recordingId)
            .executeOrThrow("stop recording")
        return RecordingResult(
            status = response.status.toRecordingStatus(),
            recordingId = response.egressId.ifBlank { recordingId },
            updatedAtEpochMillis = now.toEpochMilli(),
        )
    }

    private fun ensureRoomExists(roomName: String) {
        val rooms = roomClient.listRooms(listOf(roomName)).executeOrThrow("list rooms")
        if (rooms.any { it.name == roomName }) {
            return
        }
        roomClient.createRoom(roomName).executeOrThrow("create room")
    }

    private fun createFileOutput(callId: String, now: Instant): LivekitEgress.EncodedFileOutput {
        val objectKey = buildObjectKey(callId, now)
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
            throw ApiException(HttpStatusCode.BadRequest, "Recording is disabled")
        }
        if (recordingConfig.provider != PROVIDER_LIVEKIT) {
            throw ApiException(HttpStatusCode.BadRequest, "Recording provider is not configured for LiveKit")
        }
    }

    private fun <T> Call<T>.executeOrThrow(action: String): T {
        val response = execute()
        if (!response.isSuccessful) {
            throw ApiException(
                HttpStatusCode.BadGateway,
                "LiveKit failed to $action: ${response.code()} ${response.message()}",
            )
        }
        return response.body()
            ?: throw ApiException(HttpStatusCode.BadGateway, "LiveKit returned empty response while trying to $action")
    }

    private fun LivekitEgress.EgressStatus.toRecordingStatus(): RecordingStatus = when (this) {
        LivekitEgress.EgressStatus.EGRESS_STARTING -> RecordingStatus.STARTING
        LivekitEgress.EgressStatus.EGRESS_ACTIVE -> RecordingStatus.RECORDING
        LivekitEgress.EgressStatus.EGRESS_ENDING -> RecordingStatus.STOPPING
        LivekitEgress.EgressStatus.EGRESS_COMPLETE -> RecordingStatus.STOPPED
        LivekitEgress.EgressStatus.EGRESS_FAILED,
        LivekitEgress.EgressStatus.EGRESS_ABORTED,
        LivekitEgress.EgressStatus.EGRESS_LIMIT_REACHED,
        LivekitEgress.EgressStatus.UNRECOGNIZED,
        -> RecordingStatus.FAILED
    }

    private companion object {
        const val PROVIDER_LIVEKIT = "livekit"
        const val EMPTY_LAYOUT = ""
        const val EMPTY_CUSTOM_BASE_URL = ""
        val timestampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
    }
}
