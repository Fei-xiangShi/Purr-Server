package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CallTelemetryRequestDto(
    val sampledAtEpochMillis: Long,
    val roundTripTimeMs: Double? = null,
    val jitterMs: Double? = null,
    val uplinkPacketLossPercent: Double? = null,
    val downlinkPacketLossPercent: Double? = null,
    val uplinkBitrateKbps: Double? = null,
    val downlinkBitrateKbps: Double? = null,
    val networkTransport: String? = null,
    val sendCodec: String? = null,
    val receiveCodec: String? = null,
    val networkValidated: Boolean = false,
    val networkMetered: Boolean = false,
)
