package life.fxs.purr.server.livekit

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.model.RecordingStatus

class InMemoryRecordingController(
    private val config: RecordingConfig,
    private val nowProvider: () -> Instant = Instant::now,
) : RecordingController {
    private val recordingStarts = ConcurrentHashMap<String, Long>()
    private val recordings = ConcurrentHashMap<String, ProviderRecordingResult>()

    override fun startRecording(callId: String, roomName: String): ProviderRecordingResult {
        if (!config.enabled) {
            error("In-memory recording controller cannot be used while recording is disabled")
        }
        val now = nowProvider().toEpochMilli()
        val recordingId = "${config.idPrefix}-$callId-${UUID.randomUUID().toString().take(8)}"
        recordingStarts[recordingId] = now
        return ProviderRecordingResult(
            status = RecordingStatus.RECORDING,
            recordingId = recordingId,
            updatedAtEpochMillis = now,
            objectKey = "${config.filePrefix.trimEnd('/')}/$callId/$now.ogg",
            startedAtEpochMillis = now,
        ).also { recordings[recordingId] = it }
    }

    override fun stopRecording(
        callId: String,
        roomName: String,
        currentRecordingId: String?,
    ): ProviderRecordingResult {
        if (!config.enabled) {
            error("In-memory recording controller cannot be used while recording is disabled")
        }
        val now = nowProvider().toEpochMilli()
        val startedAt = currentRecordingId?.let(recordingStarts::remove)
        return ProviderRecordingResult(
            status = RecordingStatus.STOPPED,
            recordingId = currentRecordingId,
            updatedAtEpochMillis = now,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = now,
            durationMillis = startedAt?.let { now - it },
        ).also { result ->
            currentRecordingId?.let { recordings[it] = result }
        }
    }

    override fun getRecording(recordingId: String): ProviderRecordingResult? = recordings[recordingId]
}
