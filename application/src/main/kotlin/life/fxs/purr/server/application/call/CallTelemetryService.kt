package life.fxs.purr.server.application.call

import java.time.Instant
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.CallTelemetrySample
import life.fxs.purr.server.application.port.CallTelemetryStore

class CallTelemetryService(
    private val callAccessPolicy: CallAccessPolicy,
    private val callTelemetryStore: CallTelemetryStore,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun record(userId: String, callId: String, sample: CallTelemetrySample) {
        val call = callAccessPolicy.requireAccessibleCall(userId, callId)
        if (sample.callId != callId || sample.userId != userId) invalidSample()
        val latestAllowed = (call.endedAtEpochMillis ?: nowProvider().toEpochMilli()) + DELIVERY_GRACE_MILLIS
        if (sample.sampledAtEpochMillis !in call.startedAtEpochMillis..latestAllowed) invalidSample()
        listOf(
            sample.roundTripTimeMs to MAX_LATENCY_MILLIS,
            sample.jitterMs to MAX_LATENCY_MILLIS,
            sample.uplinkPacketLossPercent to MAX_PACKET_LOSS_PERCENT,
            sample.downlinkPacketLossPercent to MAX_PACKET_LOSS_PERCENT,
            sample.uplinkBitrateKbps to MAX_BITRATE_KBPS,
            sample.downlinkBitrateKbps to MAX_BITRATE_KBPS,
        ).forEach { (value, maximum) ->
            if (value != null && (!value.isFinite() || value < 0.0 || value > maximum)) invalidSample()
        }
        if (sample.networkTransport?.length.orZero() > MAX_LABEL_LENGTH ||
            sample.sendCodec?.length.orZero() > MAX_LABEL_LENGTH ||
            sample.receiveCodec?.length.orZero() > MAX_LABEL_LENGTH
        ) {
            invalidSample()
        }
        callTelemetryStore.append(sample)
    }

    private fun invalidSample(): Nothing = throw ApplicationException(
        ApplicationError.INVALID_ARGUMENT,
        "Invalid call telemetry sample",
    )

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        const val DELIVERY_GRACE_MILLIS = 5L * 60L * 1_000L
        const val MAX_LATENCY_MILLIS = 60_000.0
        const val MAX_PACKET_LOSS_PERCENT = 100.0
        const val MAX_BITRATE_KBPS = 10_000_000.0
        const val MAX_LABEL_LENGTH = 128
    }
}
