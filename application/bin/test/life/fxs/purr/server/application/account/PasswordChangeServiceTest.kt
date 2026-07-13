package life.fxs.purr.server.application.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.AuthSessionRecord
import life.fxs.purr.server.application.port.AuthSessionStore
import life.fxs.purr.server.application.port.PasswordHasher
import life.fxs.purr.server.application.port.PasswordVerifier
import life.fxs.purr.server.application.port.UserAccountRecord
import life.fxs.purr.server.application.port.UserAccountStore

class PasswordChangeServiceTest {
    @Test
    fun `changes password and revokes all sessions`() {
        val accounts = FakeUserAccountStore()
        val sessions = FakeAuthSessionStore()
        val service = service(accounts, sessions)

        service.changePassword("user-a", "old-password", "new-password")

        assertEquals("hash:new-password", accounts.user.passwordHash)
        assertEquals(listOf("user-a"), sessions.revokedUserIds)
    }

    @Test
    fun `incorrect current password does not mutate credentials`() {
        val accounts = FakeUserAccountStore()
        val sessions = FakeAuthSessionStore()
        val service = service(accounts, sessions)

        val exception = assertFailsWith<ApplicationException> {
            service.changePassword("user-a", "incorrect", "new-password")
        }

        assertEquals(ApplicationError.FORBIDDEN, exception.error)
        assertEquals("hash:old-password", accounts.user.passwordHash)
        assertTrue(sessions.revokedUserIds.isEmpty())
    }

    @Test
    fun `rejects reused and oversized passwords`() {
        val accounts = FakeUserAccountStore()
        val sessions = FakeAuthSessionStore()
        val service = service(accounts, sessions)

        val reused = assertFailsWith<ApplicationException> {
            service.changePassword("user-a", "old-password", "old-password")
        }
        val oversized = assertFailsWith<ApplicationException> {
            service.changePassword("user-a", "old-password", "\u5bc6".repeat(25))
        }

        assertEquals(ApplicationError.INVALID_ARGUMENT, reused.error)
        assertEquals(ApplicationError.INVALID_ARGUMENT, oversized.error)
        assertTrue(sessions.revokedUserIds.isEmpty())
    }

    @Test
    fun `concurrent credential change is reported without revoking sessions`() {
        val accounts = FakeUserAccountStore(replaceSucceeds = false)
        val sessions = FakeAuthSessionStore()
        val service = service(accounts, sessions)

        val exception = assertFailsWith<ApplicationException> {
            service.changePassword("user-a", "old-password", "new-password")
        }

        assertEquals(ApplicationError.CONFLICT, exception.error)
        assertTrue(sessions.revokedUserIds.isEmpty())
    }

    private fun service(accounts: UserAccountStore, sessions: AuthSessionStore) = PasswordChangeService(
        userAccountStore = accounts,
        authSessionStore = sessions,
        passwordVerifier = PasswordVerifier { password, hash -> hash == "hash:$password" },
        passwordHasher = PasswordHasher { password -> "hash:$password" },
        transaction = object : ApplicationTransaction {
            override fun <T> execute(block: () -> T): T = block()
        },
    )
}

private class FakeUserAccountStore(
    private val replaceSucceeds: Boolean = true,
) : UserAccountStore {
    var user = UserAccountRecord(
        userId = "user-a",
        username = "user-a",
        passwordHash = "hash:old-password",
        displayName = "User A",
        avatarUrl = null,
    )

    override fun findByUsername(username: String): UserAccountRecord? = user.takeIf { it.username == username }

    override fun findById(userId: String): UserAccountRecord? = user.takeIf { it.userId == userId }

    override fun replacePasswordHash(
        userId: String,
        expectedPasswordHash: String,
        newPasswordHash: String,
    ): Boolean {
        if (!replaceSucceeds || user.userId != userId || user.passwordHash != expectedPasswordHash) return false
        user = user.copy(passwordHash = newPasswordHash)
        return true
    }

    override fun updateAvatarUrl(userId: String, avatarUrl: String): Boolean {
        if (user.userId != userId) return false
        user = user.copy(avatarUrl = avatarUrl)
        return true
    }

    override fun updateDisplayName(userId: String, displayName: String): Boolean {
        if (user.userId != userId) return false
        user = user.copy(displayName = displayName)
        return true
    }
}

private class FakeAuthSessionStore : AuthSessionStore {
    val revokedUserIds = mutableListOf<String>()

    override fun create(
        userId: String,
        refreshToken: String,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): AuthSessionRecord = error("Not used")

    override fun rotate(
        refreshToken: String,
        replacementToken: String,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): AuthSessionRecord? = error("Not used")

    override fun isActive(sessionId: String, userId: String, nowEpochMillis: Long): Boolean = false

    override fun deleteBySessionId(sessionId: String) = Unit

    override fun deleteAllByUserId(userId: String) {
        revokedUserIds += userId
    }
}
