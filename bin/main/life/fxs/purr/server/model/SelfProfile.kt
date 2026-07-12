package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class SelfProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
)
