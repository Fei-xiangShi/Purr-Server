package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CallQualitySummaryDto(
    val sampleCount: Int,
    val averageRoundTripTimeMs: Double? = null,
    val averageJitterMs: Double? = null,
    val averagePacketLossPercent: Double? = null,
    val maximumPacketLossPercent: Double? = null,
    val averageUplinkBitrateKbps: Double? = null,
    val averageDownlinkBitrateKbps: Double? = null,
    val networkTransports: List<String>,
    val codecs: List<String>,
)

@Serializable
data class CallDetailDto(
    val callId: String,
    val direction: String,
    val outcome: String,
    val requestedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long,
    val ringingDurationMillis: Long,
    val durationMillis: Long,
    val recordingStatus: String,
    val recordingCount: Int,
    val recordingAvailable: Boolean,
    val quality: CallQualitySummaryDto? = null,
)
