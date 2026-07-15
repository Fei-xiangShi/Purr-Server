package life.fxs.purr.server.application.account

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.AuthSessionResult
import life.fxs.purr.server.application.model.UserProfile
import life.fxs.purr.server.application.port.AccessTokenIssuer
import life.fxs.purr.server.application.port.AuthSessionRecord
import life.fxs.purr.server.application.port.AuthSessionStore
import life.fxs.purr.server.application.port.PasswordVerifier
import life.fxs.purr.server.application.port.UserAccountRecord
import life.fxs.purr.server.application.port.UserAccountReader

class AuthService(
    private val refreshTokenTtlSeconds: Long,
    private val userAccountReader: UserAccountReader,
    private val authSessionStore: AuthSessionStore,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val passwordVerifier: PasswordVerifier,
    private val nowProvider: () -> Instant = Instant::now,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun login(username: String, password: String): AuthSessionResult {
        val user = userAccountReader.findByUsername(username)
            ?: throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Invalid credentials")
        if (!passwordVerifier.matches(password, user.passwordHash)) {
            throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Invalid credentials")
        }
        return createSession(user)
    }

    fun refresh(refreshToken: String): AuthSessionResult {
        val now = nowProvider()
        val replacementToken = generateRefreshToken()
        val session = authSessionStore.rotate(
            refreshToken = refreshToken,
            replacementToken = replacementToken,
            createdAtEpochMillis = now.toEpochMilli(),
            expiresAtEpochMillis = now.plusSeconds(refreshTokenTtlSeconds).toEpochMilli(),
        ) ?: throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Invalid or expired refresh token")
        val user = userAccountReader.findById(session.userId)
            ?: throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Unknown user")
        return createSessionResult(user, session, replacementToken)
    }

    fun logout(sessionId: String) = authSessionStore.deleteBySessionId(sessionId)

    fun isSessionActive(userId: String, sessionId: String): Boolean = authSessionStore.isActive(
        sessionId = sessionId,
        userId = userId,
        nowEpochMillis = nowProvider().toEpochMilli(),
    )

    private fun createSession(user: UserAccountRecord): AuthSessionResult {
        val now = nowProvider()
        val refreshToken = generateRefreshToken()
        val storedSession = authSessionStore.create(
            userId = user.userId,
            refreshToken = refreshToken,
            createdAtEpochMillis = now.toEpochMilli(),
            expiresAtEpochMillis = now.plusSeconds(refreshTokenTtlSeconds).toEpochMilli(),
        )
        return createSessionResult(user, storedSession, refreshToken)
    }

    private fun createSessionResult(
        user: UserAccountRecord,
        storedSession: AuthSessionRecord,
        refreshToken: String,
    ) = AuthSessionResult(
        accessToken = accessTokenIssuer.issueAccessToken(user.userId, storedSession.sessionId),
        refreshToken = refreshToken,
        self = UserProfile(user.userId, user.displayName, user.avatarUrl),
    )

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
