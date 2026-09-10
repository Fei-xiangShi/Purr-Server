package life.fxs.purr.server.mediamtx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import life.fxs.purr.server.application.port.ScreenShareTokenRequest
import life.fxs.purr.server.config.MediaMtxConfig
import life.fxs.purr.server.model.ScreenSharePurpose

class JwtScreenShareTokenServiceTest {
    private val service = JwtScreenShareTokenService(
        MediaMtxConfig(tokenSecret = "screen-share-test-secret-with-at-least-32-bytes"),
    )

    @Test
    fun `token round trip preserves share call path purpose and expiry`() {
        val expiresAt = ((System.currentTimeMillis() + 60_000) / 1_000) * 1_000
        val token = service.issue(
            ScreenShareTokenRequest(
                shareId = "share-1",
                callId = "call-1",
                userId = "user-a",
                mediaPath = "screen-share-1",
                purpose = ScreenSharePurpose.PUBLISH,
                expiresAtEpochMillis = expiresAt,
            ),
        )

        val claims = assertNotNull(service.verify(token))
        assertEquals("share-1", claims.shareId)
        assertEquals("call-1", claims.callId)
        assertEquals("user-a", claims.userId)
        assertEquals("screen-share-1", claims.mediaPath)
        assertEquals(ScreenSharePurpose.PUBLISH, claims.purpose)
        assertEquals(expiresAt, claims.expiresAtEpochMillis)
        assertTrue(token.length < 512, "SRT stream IDs have a 512-character limit")
    }

    @Test
    fun `expired or tampered tokens are rejected`() {
        val expired = service.issue(
            ScreenShareTokenRequest(
                shareId = "share-1",
                callId = "call-1",
                userId = "user-a",
                mediaPath = "screen-share-1",
                purpose = ScreenSharePurpose.READ,
                expiresAtEpochMillis = System.currentTimeMillis() - 1_000,
            ),
        )

        assertNull(service.verify(expired))
        assertNull(service.verify(expired.dropLast(1) + if (expired.last() == 'a') 'b' else 'a'))
    }

    @Test
    fun `srt passphrase is deterministic bounded and share specific`() {
        val first = service.issue("share-1")

        assertEquals(first, service.issue("share-1"))
        assertNotEquals(first, service.issue("share-2"))
        assertTrue(first.length in 10..79)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
