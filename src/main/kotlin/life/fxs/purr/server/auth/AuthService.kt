package life.fxs.purr.server.auth

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.config.AuthConfig
import life.fxs.purr.server.model.AuthSessionDto
import life.fxs.purr.server.model.LoginRequestDto
import life.fxs.purr.server.model.RefreshRequestDto
import life.fxs.purr.server.repository.AuthSessionRepository
import life.fxs.purr.server.repository.StoredUser
import life.fxs.purr.server.repository.UserRepository
import org.mindrot.jbcrypt.BCrypt

class AuthService(
    private val config: AuthConfig,
    private val userRepository: UserRepository,
    private val authSessionRepository: AuthSessionRepository,
    private val jwtTokenService: JwtTokenService,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun login(request: LoginRequestDto): AuthSessionDto {
        val user = userRepository.findByUsername(request.username)
            ?: throw ApiException(HttpStatusCode.Unauthorized, "Invalid credentials")
        if (!BCrypt.checkpw(request.password, user.passwordHash)) {
            throw ApiException(HttpStatusCode.Unauthorized, "Invalid credentials")
        }
        return createSession(user)
    }

    fun refresh(request: RefreshRequestDto): AuthSessionDto {
        val session = authSessionRepository.findByRefreshToken(request.refreshToken)
            ?: throw ApiException(HttpStatusCode.Unauthorized, "Invalid refresh token")
        val now = nowProvider().toEpochMilli()
        if (session.expiresAtEpochMillis <= now) {
            authSessionRepository.deleteBySessionId(session.sessionId)
            throw ApiException(HttpStatusCode.Unauthorized, "Refresh token expired")
        }
        val user = userRepository.findById(session.userId)
            ?: throw ApiException(HttpStatusCode.Unauthorized, "Unknown user")
        authSessionRepository.deleteBySessionId(session.sessionId)
        return createSession(user)
    }

    fun logout(sessionId: String) {
        authSessionRepository.deleteBySessionId(sessionId)
    }

    private fun createSession(user: StoredUser): AuthSessionDto {
        val now = nowProvider()
        val refreshToken = UUID.randomUUID().toString()
        val storedSession = authSessionRepository.create(
            userId = user.userId,
            refreshToken = refreshToken,
            createdAtEpochMillis = now.toEpochMilli(),
            expiresAtEpochMillis = now.plusSeconds(config.accessTokenTtlSeconds * 24).toEpochMilli(),
        )
        return AuthSessionDto(
            accessToken = jwtTokenService.issueAccessToken(
                userId = user.userId,
                sessionId = storedSession.sessionId,
            ),
            refreshToken = refreshToken,
            self = user.toSelfProfile(),
        )
    }
}
