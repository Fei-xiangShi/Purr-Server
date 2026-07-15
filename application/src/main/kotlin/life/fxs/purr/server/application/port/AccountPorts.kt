package life.fxs.purr.server.application.port

data class UserAccountRecord(
    val userId: String,
    val username: String,
    val passwordHash: String,
    val displayName: String,
    val avatarUrl: String?,
    val avatarObjectKey: String? = null,
    val avatarVersion: Long = 0,
)

interface UserAccountReader {
    fun findByUsername(username: String): UserAccountRecord?

    fun findById(userId: String): UserAccountRecord?
}

interface UserCredentialStore {
    fun replacePasswordHash(userId: String, expectedPasswordHash: String, newPasswordHash: String): Boolean
}

interface UserProfileStore {
    fun updateDisplayName(userId: String, displayName: String): Boolean

    fun compareAndSetAvatar(
        userId: String,
        expectedVersion: Long,
        objectKey: String,
    ): Boolean
}

fun interface AvatarReferenceReader {
    fun findReferencedObjectKeys(candidates: Set<String>): Set<String>
}

interface UserAccountStore : UserAccountReader, UserCredentialStore, UserProfileStore, AvatarReferenceReader

data class AuthSessionRecord(
    val sessionId: String,
    val userId: String,
    val refreshTokenHash: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

interface AuthSessionStore {
    fun create(
        userId: String,
        refreshToken: String,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): AuthSessionRecord

    fun rotate(
        refreshToken: String,
        replacementToken: String,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): AuthSessionRecord?

    fun isActive(sessionId: String, userId: String, nowEpochMillis: Long): Boolean

    fun deleteBySessionId(sessionId: String)

    fun deleteAllByUserId(userId: String)
}

data class PairRecord(
    val pairId: String,
    val userAId: String,
    val userBId: String,
    val bondedAtEpochMillis: Long,
)

interface PairStore {
    fun findByUserId(userId: String): PairRecord?

    fun findByPairId(pairId: String): PairRecord?
}

interface PresenceStore {
    fun connect(connectionId: String, userId: String, nowEpochMillis: Long)

    fun heartbeat(connectionId: String, nowEpochMillis: Long)

    fun disconnect(connectionId: String)

    fun isOnline(userId: String, nowEpochMillis: Long): Boolean
}

fun interface AccessTokenIssuer {
    fun issueAccessToken(userId: String, sessionId: String): String
}

fun interface PasswordVerifier {
    fun matches(password: String, passwordHash: String): Boolean
}

fun interface PasswordHasher {
    fun hash(password: String): String
}
