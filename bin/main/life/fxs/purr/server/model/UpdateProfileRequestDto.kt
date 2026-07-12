package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequestDto(
    val displayName: String,
)
