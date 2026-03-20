package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    val self: SelfProfile,
)
