package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CallRecordingDto(
    val recordingId: String,
    val callId: String,
    val status: String,
    val downloadAvailable: Boolean,
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val durationMillis: Long? = null,
    val sizeBytes: Long? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

@Serializable
data class CallRecordingsResponseDto(
    val recordings: List<CallRecordingDto>,
)

@Serializable
data class RecordingDownloadDto(
    val recordingId: String,
    val url: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class RecordingLibraryResponseDto(
    val recordings: List<CallRecordingDto>,
    val nextCursor: String? = null,
)
