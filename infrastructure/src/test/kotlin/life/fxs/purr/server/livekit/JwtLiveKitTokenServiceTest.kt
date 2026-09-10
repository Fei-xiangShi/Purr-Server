package life.fxs.purr.server.livekit

import com.auth0.jwt.JWT
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import life.fxs.purr.server.config.LiveKitConfig

class JwtLiveKitTokenServiceTest {
    @Test
    fun `issued participant token can update its own metadata`() {
        val service = JwtLiveKitTokenService(
            config = LiveKitConfig(
                wsUrl = "wss://livekit.invalid",
                apiKey = "test-key",
                apiSecret = "test-secret-with-enough-entropy",
                tokenTtlSeconds = 3_600L,
                httpUrl = "https://livekit.invalid",
            ),
            nowProvider = { Instant.parse("2026-08-29T00:00:00Z") },
        )

        val token = service.issueAccessToken(
            roomName = "room-1",
            participantIdentity = "participant-1",
        )
        val decoded = JWT.decode(token)
        val videoGrant = decoded.getClaim("video").asMap()

        assertEquals("participant-1", decoded.subject)
        assertEquals("room-1", videoGrant["room"])
        assertEquals(true, videoGrant["roomJoin"])
        assertEquals(true, videoGrant["canPublish"])
        assertEquals(true, videoGrant["canSubscribe"])
        assertEquals(true, videoGrant["canPublishData"])
        assertEquals(true, videoGrant["canUpdateOwnMetadata"])
        assertTrue(decoded.expiresAt.toInstant().isAfter(Instant.parse("2026-08-29T00:00:00Z")))
    }
}
