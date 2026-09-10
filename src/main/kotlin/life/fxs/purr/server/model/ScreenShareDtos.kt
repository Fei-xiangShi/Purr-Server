package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateScreenShareRequestDto(
    val source: String,
)

@Serializable
data class ScreenShareEnvelopeDto(
    val screenShare: ScreenShareDto?,
)

@Serializable
data class ScreenShareDto(
    val shareId: String,
    val callId: String,
    val ownerUserId: String,
    val source: String,
    val status: String,
    val mediaPath: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val liveAtEpochMillis: Long? = null,
    val stoppedAtEpochMillis: Long? = null,
    val publishing: ScreenSharePublishingDto? = null,
    val playback: ScreenShareMediaEndpointDto? = null,
    val errorMessage: String? = null,
)

@Serializable
data class ScreenSharePublishingDto(
    val whip: ScreenShareMediaEndpointDto,
    val srt: ScreenShareSrtDto? = null,
)

@Serializable
data class ScreenShareMediaEndpointDto(
    val url: String,
    val bearerToken: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class ScreenShareSrtDto(
    val url: String,
    val streamId: String,
    val passphrase: String,
)

@Serializable
data class MediaMtxAuthRequestDto(
    val user: String = "",
    val password: String = "",
    val token: String = "",
    val ip: String = "",
    val action: String = "",
    val path: String = "",
    val protocol: String = "",
    // MediaMTX authenticates WHEP access before a WebRTC session exists, so
    // the provider contract intentionally sends this field as JSON null.
    val id: String? = null,
    val query: String = "",
    val userAgent: String = "",
)
