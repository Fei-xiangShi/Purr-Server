package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CallHistoryItemDto(
    val callId: String,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
)

@Serializable
data class CallHistoryResponseDto(
    val calls: List<CallHistoryItemDto>,
    val nextCursor: String? = null,
)
