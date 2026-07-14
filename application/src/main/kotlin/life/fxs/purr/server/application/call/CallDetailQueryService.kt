package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.model.CallDetailResult
import life.fxs.purr.server.application.model.CallQualitySummaryResult
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.CallTelemetrySample
import life.fxs.purr.server.application.port.CallTelemetryStore
import life.fxs.purr.server.model.RecordingStatus

class CallDetailQueryService(
    private val callAccessPolicy: CallAccessPolicy,
    private val callRecordingStore: CallRecordingStore,
    private val callTelemetryStore: CallTelemetryStore,
) {
    fun getDetail(userId: String, callId: String): CallDetailResult {
        val call = callAccessPolicy.requireAccessibleCall(userId, callId)
        if (call.endedAtEpochMillis == null) {
            throw ApplicationException(ApplicationError.CONFLICT, "Call detail is available after the call ends")
        }
        val item = call.toHistoryItem(userId)
        val recordings = callRecordingStore.findByCallId(callId)
        return CallDetailResult(
            callId = item.callId,
            direction = item.direction,
            outcome = item.outcome,
            requestedAtEpochMillis = item.requestedAtEpochMillis,
            connectedAtEpochMillis = item.connectedAtEpochMillis,
            endedAtEpochMillis = item.endedAtEpochMillis,
            ringingDurationMillis = item.ringingDurationMillis,
            durationMillis = item.durationMillis,
            recordingStatus = item.recordingStatus,
            recordingCount = recordings.size,
            recordingAvailable = recordings.any {
                it.status == RecordingStatus.STOPPED && !it.objectKey.isNullOrBlank()
            },
            quality = callTelemetryStore.findByCallId(callId).toQualitySummary(),
        )
    }
}

private fun List<CallTelemetrySample>.toQualitySummary(): CallQualitySummaryResult? {
    if (isEmpty()) return null
    val packetLoss = flatMap { listOfNotNull(it.uplinkPacketLossPercent, it.downlinkPacketLossPercent) }
    return CallQualitySummaryResult(
        sampleCount = size,
        averageRoundTripTimeMs = mapNotNull { it.roundTripTimeMs }.averageOrNull(),
        averageJitterMs = mapNotNull { it.jitterMs }.averageOrNull(),
        averagePacketLossPercent = packetLoss.averageOrNull(),
        maximumPacketLossPercent = packetLoss.maxOrNull(),
        averageUplinkBitrateKbps = mapNotNull { it.uplinkBitrateKbps }.averageOrNull(),
        averageDownlinkBitrateKbps = mapNotNull { it.downlinkBitrateKbps }.averageOrNull(),
        networkTransports = mapNotNull { it.networkTransport }.distinct().sorted(),
        codecs = flatMap { listOfNotNull(it.sendCodec, it.receiveCodec) }.distinct().sorted(),
    )
}

private fun List<Double>.averageOrNull(): Double? = takeIf { it.isNotEmpty() }?.average()
