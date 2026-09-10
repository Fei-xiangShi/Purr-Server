package life.fxs.purr.server.mediamtx

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import life.fxs.purr.server.application.port.ScreenShareSrtPassphraseIssuer
import life.fxs.purr.server.application.port.ScreenShareTokenClaims
import life.fxs.purr.server.application.port.ScreenShareTokenIssuer
import life.fxs.purr.server.application.port.ScreenShareTokenRequest
import life.fxs.purr.server.application.port.ScreenShareTokenVerifier
import life.fxs.purr.server.config.MediaMtxConfig
import life.fxs.purr.server.model.ScreenSharePurpose

class JwtScreenShareTokenService(
    private val config: MediaMtxConfig,
) : ScreenShareTokenIssuer, ScreenShareTokenVerifier, ScreenShareSrtPassphraseIssuer {
    private val algorithm = Algorithm.HMAC256(config.tokenSecret)
    private val verifier = JWT.require(algorithm)
        .withIssuer(config.tokenIssuer)
        .withAudience(config.tokenAudience)
        .build()

    override fun issue(request: ScreenShareTokenRequest): String = JWT.create()
        .withIssuer(config.tokenIssuer)
        .withAudience(config.tokenAudience)
        .withSubject(request.userId)
        .withClaim(CLAIM_SHARE_ID, request.shareId)
        .withClaim(CLAIM_CALL_ID, request.callId)
        .withClaim(CLAIM_PATH, request.mediaPath)
        .withClaim(CLAIM_PURPOSE, request.purpose.wireValue)
        .withIssuedAt(Date())
        .withExpiresAt(Date(request.expiresAtEpochMillis))
        .sign(algorithm)

    override fun verify(token: String): ScreenShareTokenClaims? {
        return try {
            val decoded = verifier.verify(token)
            val purpose = ScreenSharePurpose.entries.firstOrNull {
                it.wireValue == decoded.getClaim(CLAIM_PURPOSE).asString()
            } ?: return null
            ScreenShareTokenClaims(
                shareId = decoded.getClaim(CLAIM_SHARE_ID).asString() ?: return null,
                callId = decoded.getClaim(CLAIM_CALL_ID).asString() ?: return null,
                userId = decoded.subject ?: return null,
                mediaPath = decoded.getClaim(CLAIM_PATH).asString() ?: return null,
                purpose = purpose,
                expiresAtEpochMillis = decoded.expiresAt?.time ?: return null,
            )
        } catch (_: JWTVerificationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun issue(shareId: String): String {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(config.tokenSecret.toByteArray(Charsets.UTF_8), HMAC_SHA_256))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("srt:$shareId".toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val CLAIM_SHARE_ID = "sid"
        const val CLAIM_CALL_ID = "cid"
        const val CLAIM_PATH = "pth"
        const val CLAIM_PURPOSE = "scp"
        const val HMAC_SHA_256 = "HmacSHA256"
    }
}
