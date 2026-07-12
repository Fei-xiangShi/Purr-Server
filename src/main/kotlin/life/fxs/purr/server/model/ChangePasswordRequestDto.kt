package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class ChangePasswordRequestDto(
    val currentPassword: String,
    val newPassword: String,
)
