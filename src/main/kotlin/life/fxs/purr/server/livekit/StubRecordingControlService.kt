package life.fxs.purr.server.livekit

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.model.RecordingStatus

class StubRecordingControlService(
    private val config: RecordingConfig,
    private val nowProvider: () -> Instant = Instant::now,
) : RecordingControlService {
    override fun startRecording(callId: String, roomName: String): RecordingResult {
        if (!config.enabled) {
            throw ApiException(HttpStatusCode.BadRequest, "Recording is disabled")
        }
        return RecordingResult(
            status = RecordingStatus.RECORDING,
            recordingId = "${config.idPrefix}-$callId-${UUID.randomUUID().toString().take(8)}",
            updatedAtEpochMillis = nowProvider().toEpochMilli(),
        )
    }

    override fun stopRecording(callId: String, roomName: String, currentRecordingId: String?): RecordingResult {
        if (!config.enabled) {
            throw ApiException(HttpStatusCode.BadRequest, "Recording is disabled")
        }
        return RecordingResult(
            status = RecordingStatus.STOPPED,
            recordingId = currentRecordingId,
            updatedAtEpochMillis = nowProvider().toEpochMilli(),
        )
    }
}
