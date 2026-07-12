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
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
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
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
)

data class CallHistoryResult(
    val calls: List<CallHistoryItemResult>,
    val nextCursor: String?,
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
