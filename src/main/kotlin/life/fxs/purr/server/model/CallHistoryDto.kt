package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CallHistoryItemDto(
    val callId: String,
    val direction: String,
    val outcome: String,
    val requestedAtEpochMillis: Long,
    val startedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long,
    val ringingDurationMillis: Long,
    val durationMillis: Long,
    val recordingStatus: String,
)

@Serializable
data class CallHistoryResponseDto(
    val calls: List<CallHistoryItemDto>,
    val nextCursor: String? = null,
)

@Serializable
data class CallCalendarDayDto(
    val date: String,
    val callCount: Int,
    val totalDurationMillis: Long,
)

@Serializable
data class CallCalendarResponseDto(
    val days: List<CallCalendarDayDto>,
)
