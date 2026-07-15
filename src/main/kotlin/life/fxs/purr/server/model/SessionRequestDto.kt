package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionRequestDto(
    val pairId: String,
    val expectedCallId: String? = null,
    val recordingConsent: Boolean = false,
)
