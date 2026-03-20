package life.fxs.purr.server.livekit

interface LiveKitTokenService {
    fun issueAccessToken(
        roomName: String,
        participantIdentity: String,
    ): String
}
