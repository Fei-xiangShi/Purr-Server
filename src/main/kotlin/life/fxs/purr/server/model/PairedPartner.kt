package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class PairedPartner(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isOnline: Boolean = true,
    val isCallable: Boolean = true,
)
