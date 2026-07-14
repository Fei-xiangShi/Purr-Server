package life.fxs.purr.server.application.port

data class CallTelemetrySample(
    val callId: String,
    val userId: String,
    val sampledAtEpochMillis: Long,
    val roundTripTimeMs: Double?,
    val jitterMs: Double?,
    val uplinkPacketLossPercent: Double?,
    val downlinkPacketLossPercent: Double?,
    val uplinkBitrateKbps: Double?,
    val downlinkBitrateKbps: Double?,
    val networkTransport: String?,
    val sendCodec: String?,
    val receiveCodec: String?,
    val networkValidated: Boolean,
    val networkMetered: Boolean,
)

interface CallTelemetryStore {
    /** Idempotently persists one participant sample. */
    fun append(sample: CallTelemetrySample)

    fun findByCallId(callId: String): List<CallTelemetrySample>
}
