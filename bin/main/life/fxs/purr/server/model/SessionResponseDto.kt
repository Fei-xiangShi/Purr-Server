package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionResponseDto(
    val callId: String,
    val pairId: String,
    val roomName: String,
    val participantIdentity: String,
    val token: String,
    val wsUrl: String,
)
