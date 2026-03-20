package life.fxs.purr.server.livekit

import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanPublishData
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName
import java.time.Instant
import java.util.Date
import life.fxs.purr.server.config.LiveKitConfig

class JwtLiveKitTokenService(
    private val config: LiveKitConfig,
    private val nowProvider: () -> Instant = Instant::now,
) : LiveKitTokenService {
    override fun issueAccessToken(roomName: String, participantIdentity: String): String {
        val now = nowProvider()
        return AccessToken(config.apiKey, config.apiSecret).apply {
            identity = participantIdentity
            expiration = Date.from(now.plusSeconds(config.tokenTtlSeconds))
            addGrants(
                RoomJoin(true),
                RoomName(roomName),
                CanPublish(true),
                CanSubscribe(true),
                CanPublishData(true),
            )
        }.toJwt()
    }
}
