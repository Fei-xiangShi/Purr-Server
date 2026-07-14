package life.fxs.purr.server.application.model

import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

data class CreateCallSessionCommand(
    val pairId: String,
    val resumeCallId: String?,
    val recordingConsent: Boolean,
)

data class CallSessionResult(
    val callId: String,
    val pairId: String,
    val roomName: String,
    val participantIdentity: String,
    val token: String,
    val wsUrl: String,
)

data class CallStatusResult(
    val callId: String,
    val pairId: String,
    val state: CallState,
    val recordingStatus: RecordingStatus,
    val startedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val durationMillis: Long?,
    val serverNowEpochMillis: Long,
)

data class ActiveCallResult(
    val callId: String,
    val pairId: String,
    val callerUserId: String,
    val isIncoming: Boolean,
    val startedAtEpochMillis: Long,
)

data class CallHistoryItemResult(
    val callId: String,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val requestedAtEpochMillis: Long,
    val startedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long,
    val ringingDurationMillis: Long,
    val durationMillis: Long,
    val recordingStatus: RecordingStatus,
)

data class CallHistoryResult(
    val calls: List<CallHistoryItemResult>,
    val nextCursor: String?,
)

enum class CallDirection(val wireValue: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing"),
}

enum class CallOutcome(val wireValue: String) {
    COMPLETED("completed"),
    MISSED("missed"),
    CANCELLED("cancelled"),
}

data class CallCalendarDayResult(
    val date: String,
    val callCount: Int,
    val totalDurationMillis: Long,
)

data class CallCalendarResult(
    val days: List<CallCalendarDayResult>,
)

data class CallQualitySummaryResult(
    val sampleCount: Int,
    val averageRoundTripTimeMs: Double?,
    val averageJitterMs: Double?,
    val averagePacketLossPercent: Double?,
    val maximumPacketLossPercent: Double?,
    val averageUplinkBitrateKbps: Double?,
    val averageDownlinkBitrateKbps: Double?,
    val networkTransports: List<String>,
    val codecs: List<String>,
)

data class CallDetailResult(
    val callId: String,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val requestedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long,
    val ringingDurationMillis: Long,
    val durationMillis: Long,
    val recordingStatus: RecordingStatus,
    val recordingCount: Int,
    val recordingAvailable: Boolean,
    val quality: CallQualitySummaryResult?,
)

data class RecordingResultView(
    val callId: String,
    val status: RecordingStatus,
    val recordingId: String?,
    val updatedAtEpochMillis: Long,
)

data class CallRecordingResult(
    val recordingId: String,
    val callId: String,
    val status: RecordingStatus,
    val downloadAvailable: Boolean,
    val startedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val durationMillis: Long?,
    val sizeBytes: Long?,
    val errorCode: Int?,
    val errorMessage: String?,
)

data class RecordingDownloadResult(
    val recordingId: String,
    val url: String,
    val expiresAtEpochMillis: Long,
)
