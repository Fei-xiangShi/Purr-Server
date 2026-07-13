package life.fxs.purr.server.livekit

import io.livekit.server.AudioMixing
import io.livekit.server.EgressServiceClient
import io.livekit.server.RoomServiceClient
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
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
    /** Fast-path cache; cross-process reconciliation uses the deterministic output key below. */
    private val operationResults = ConcurrentHashMap<String, ProviderRecordingResult>()
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
        val now = nowProvider()
        val objectKey = buildObjectKey(callId, now)
        return startRecordingWithObjectKey(roomName, objectKey)
    }

    private fun startRecordingWithObjectKey(
        roomName: String,
        objectKey: String,
    ): ProviderRecordingResult {
        ensureEnabled()
        ensureRoomExists(roomName)
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

    override fun startRecording(
        callId: String,
        roomName: String,
        operationId: String,
    ): ProviderRecordingResult = operationResults[operationId]
        ?: findRecordingForOperation(callId, roomName, operationId)
        ?: startRecordingWithObjectKey(
            roomName = roomName,
            objectKey = buildOperationObjectKey(callId, operationId),
        ).also { operationResults[operationId] = it }

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

    override fun stopRecording(
        callId: String,
        roomName: String,
        currentRecordingId: String?,
        operationId: String,
    ): ProviderRecordingResult = operationResults[operationId] ?: stopRecording(
        callId,
        roomName,
        currentRecordingId,
    ).also { operationResults[operationId] = it }

    override fun getRecording(recordingId: String): ProviderRecordingResult? {
        ensureEnabled()
        return client.listEgress("", recordingId, null)
            .executeOrThrow("get recording status")
            .firstOrNull { it.egressId == recordingId }
            ?.toRecordingResult()
    }

    override fun findRecordingForOperation(
        callId: String,
        roomName: String,
        operationId: String,
    ): ProviderRecordingResult? {
        ensureEnabled()
        val objectKey = buildOperationObjectKey(callId, operationId)
        return client.listEgress(roomName, "", null)
            .executeOrThrow("reconcile recording start")
            .firstOrNull { it.targetsObjectKey(objectKey) }
            ?.toRecordingResult(knownObjectKey = objectKey)
            ?.also { operationResults[operationId] = it }
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

    private fun buildOperationObjectKey(callId: String, operationId: String): String {
        return recordingOperationObjectKey(recordingConfig.filePrefix, callId, operationId)
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

internal fun recordingOperationObjectKey(
    filePrefix: String,
    callId: String,
    operationId: String,
): String {
    val safeOperationId = operationId.replace(UNSAFE_OBJECT_KEY_CHARACTER, "_")
    return "${filePrefix.trimEnd('/')}/$callId/$safeOperationId.ogg"
}

@Suppress("DEPRECATION")
internal fun LivekitEgress.EgressInfo.targetsObjectKey(objectKey: String): Boolean {
    if (fileResultsList.any { it.filename == objectKey }) return true
    if (!hasRoomComposite()) return false
    val request = roomComposite
    return (request.hasFile() && request.file.filepath == objectKey) ||
        request.fileOutputsList.any { it.filepath == objectKey }
}

private val UNSAFE_OBJECT_KEY_CHARACTER = Regex("[^A-Za-z0-9._-]")
