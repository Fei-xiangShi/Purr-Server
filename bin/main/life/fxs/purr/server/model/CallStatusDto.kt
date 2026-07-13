package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CallStatusDto(
    val callId: String,
    val pairId: String,
    val state: String,
    val recordingStatus: String,
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val durationMillis: Long? = null,
    val serverNowEpochMillis: Long,
)
