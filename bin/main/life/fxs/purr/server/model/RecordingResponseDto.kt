package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class RecordingResponseDto(
    val callId: String,
    val status: String,
    val recordingId: String? = null,
    val updatedAtEpochMillis: Long,
)
