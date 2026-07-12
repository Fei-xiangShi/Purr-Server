package life.fxs.purr.server.livekit

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.model.RecordingStatus
import livekit.LivekitEgress

internal fun LivekitEgress.EgressInfo.toRecordingResult(
    knownObjectKey: String? = null,
): ProviderRecordingResult {
    val fileInfo = fileResultsList.lastOrNull()
    val sourceUpdatedAt = updatedAt.fromLiveKitNanoseconds()
    val mappedStatus = status.toRecordingStatus()
    return ProviderRecordingResult(
        status = mappedStatus,
        recordingId = egressId.takeIf { it.isNotBlank() },
        updatedAtEpochMillis = sourceUpdatedAt ?: throw ApplicationException(
            ApplicationError.EXTERNAL_DEPENDENCY,
            "LiveKit recording response is missing updated_at",
        ),
        objectKey = knownObjectKey ?: fileInfo?.filename?.takeIf { it.isNotBlank() },
        location = fileInfo?.location?.takeIf { it.isNotBlank() },
        startedAtEpochMillis = fileInfo?.startedAt?.fromLiveKitNanoseconds()
            ?: startedAt.fromLiveKitNanoseconds(),
        endedAtEpochMillis = fileInfo?.endedAt?.fromLiveKitNanoseconds()
            ?: endedAt.fromLiveKitNanoseconds(),
        durationMillis = fileInfo?.duration?.fromLiveKitNanoseconds(),
        sizeBytes = fileInfo?.size?.takeIf { it > 0 },
        errorCode = errorCode.takeIf { mappedStatus == RecordingStatus.FAILED && it != 0 },
        errorMessage = if (mappedStatus == RecordingStatus.FAILED) {
            error.takeIf { it.isNotBlank() } ?: details.takeIf { it.isNotBlank() }
        } else {
            null
        },
    )
}

internal fun LivekitEgress.EgressStatus.toRecordingStatus(): RecordingStatus = when (this) {
    LivekitEgress.EgressStatus.EGRESS_STARTING -> RecordingStatus.STARTING
    LivekitEgress.EgressStatus.EGRESS_ACTIVE -> RecordingStatus.RECORDING
    LivekitEgress.EgressStatus.EGRESS_ENDING -> RecordingStatus.STOPPING
    LivekitEgress.EgressStatus.EGRESS_COMPLETE -> RecordingStatus.STOPPED
    LivekitEgress.EgressStatus.EGRESS_FAILED,
    LivekitEgress.EgressStatus.EGRESS_ABORTED,
    LivekitEgress.EgressStatus.EGRESS_LIMIT_REACHED,
    LivekitEgress.EgressStatus.UNRECOGNIZED,
    -> RecordingStatus.FAILED
}

private fun Long.fromLiveKitNanoseconds(): Long? =
    takeIf { it > 0 }?.div(NANOSECONDS_PER_MILLISECOND)

private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
