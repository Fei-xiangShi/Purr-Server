package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.CallRecordingResult
import life.fxs.purr.server.application.model.RecordingDownloadResult
import life.fxs.purr.server.application.model.RecordingLibraryResult
import life.fxs.purr.server.application.model.RecordingPageCursor
import life.fxs.purr.server.application.model.RecordingPageCursorCodec
import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.RecordingDownloadProvider
import life.fxs.purr.server.application.port.RecordingRecord
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.application.account.PairService

class RecordingQueryService(
    private val callAccessPolicy: CallAccessPolicy,
    private val pairService: PairService,
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

    fun getRecordingLibrary(
        userId: String,
        limit: Int,
        cursor: RecordingPageCursor?,
    ): RecordingLibraryResult {
        val pairId = pairService.requirePairId(userId)
        val page = callRecordingStore.findByPairId(pairId, limit + 1, cursor)
        val hasMore = page.size > limit
        val recordings = page.take(limit)
        return RecordingLibraryResult(
            recordings = recordings.map { it.toResult() },
            nextCursor = recordings.lastOrNull()?.takeIf { hasMore }?.let { recording ->
                RecordingPageCursorCodec.encode(
                    RecordingPageCursor(recording.createdAtEpochMillis, recording.recordingId),
                )
            },
        )
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
