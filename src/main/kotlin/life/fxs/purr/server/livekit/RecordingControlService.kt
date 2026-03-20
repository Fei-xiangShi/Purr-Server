package life.fxs.purr.server.livekit

import life.fxs.purr.server.model.RecordingStatus

data class RecordingResult(
    val status: RecordingStatus,
    val recordingId: String?,
    val updatedAtEpochMillis: Long,
)

interface RecordingControlService {
    fun startRecording(callId: String, roomName: String): RecordingResult

    fun stopRecording(callId: String, roomName: String, currentRecordingId: String?): RecordingResult
}
