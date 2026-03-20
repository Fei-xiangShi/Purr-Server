package life.fxs.purr.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.time.Instant
import life.fxs.purr.server.config.AuthConfig

class JwtTokenService(
    private val config: AuthConfig,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    fun issueAccessToken(userId: String, sessionId: String): String {
        val now = nowProvider()
        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId)
            .withClaim("sessionId", sessionId)
            .withClaim("type", "access")
            .withIssuedAt(java.util.Date.from(now))
            .withExpiresAt(java.util.Date.from(now.plusSeconds(config.accessTokenTtlSeconds)))
            .sign(algorithm)
    }

    fun verifier() = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaim("type", "access")
        .build()

    fun decodeSubject(jwt: DecodedJWT): String = jwt.subject
}
