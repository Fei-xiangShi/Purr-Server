package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class ActiveCallDto(
    val callId: String,
    val pairId: String,
    val callerUserId: String,
    val isIncoming: Boolean,
    val startedAtEpochMillis: Long,
)

@Serializable
data class ActiveCallResponseDto(
    val activeCall: ActiveCallDto? = null,
)
