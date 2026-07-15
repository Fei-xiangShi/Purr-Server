package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class PushDeviceRegistrationDto(
    val provider: String,
    val token: String,
)
