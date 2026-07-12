package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.CallRecordingResult
import life.fxs.purr.server.application.model.RecordingDownloadResult
import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.RecordingDownloadProvider
import life.fxs.purr.server.application.port.RecordingRecord
import life.fxs.purr.server.model.RecordingStatus

class RecordingQueryService(
    private val callAccessPolicy: CallAccessPolicy,
    private val callRecordingStore: CallRecordingStore,
    private val recordingDownloadProvider: RecordingDownloadProvider,
) {
    fun getRecordings(userId: String, callId: String): List<CallRecordingResult> {
        callAccessPolicy.requireAccessibleCall(userId, callId)
        return callRecordingStore.findByCallId(callId).map { it.toResult() }
    }

    fun getRecordingDownload(
        userId: String,
        callId: String,
        recordingId: String,
    ): RecordingDownloadResult {
        callAccessPolicy.requireAccessibleCall(userId, callId)
        val recording = callRecordingStore.findByRecordingId(recordingId)
            ?.takeIf { it.callId == callId }
            ?: throw ApplicationException(ApplicationError.NOT_FOUND, "Recording not found: $recordingId")
        if (recording.status != RecordingStatus.STOPPED || recording.objectKey.isNullOrBlank()) {
            throw ApplicationException(
                ApplicationError.CONFLICT,
                "Recording is not ready for download: $recordingId",
            )
        }
        return recordingDownloadProvider.create(recordingId, recording.objectKey)
    }

    private fun RecordingRecord.toResult() = CallRecordingResult(
        recordingId = recordingId,
        callId = callId,
        status = status,
        downloadAvailable = status == RecordingStatus.STOPPED && !objectKey.isNullOrBlank(),
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        durationMillis = durationMillis,
        sizeBytes = sizeBytes,
        errorCode = errorCode,
        errorMessage = GENERIC_RECORDING_FAILURE.takeIf { status == RecordingStatus.FAILED },
    )

    private companion object {
        const val GENERIC_RECORDING_FAILURE = "Recording processing failed"
    }
}
