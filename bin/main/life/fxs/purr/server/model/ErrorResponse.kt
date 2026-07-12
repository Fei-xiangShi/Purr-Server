package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val requestId: String? = null,
)
