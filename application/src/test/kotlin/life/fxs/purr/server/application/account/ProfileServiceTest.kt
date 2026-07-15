package life.fxs.purr.server.application.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.UserAccountRecord
import life.fxs.purr.server.application.port.UserAccountStore

class ProfileServiceTest {
    @Test
    fun `trims and updates display name without changing avatar`() {
        val accounts = ProfileUserAccountStore()
        val profile = service(accounts).updateDisplayName("user-a", "  New Name  ")

        assertEquals("New Name", profile.displayName)
        assertEquals("https://storage/a.png", profile.avatarUrl)
        assertEquals("New Name", accounts.user.displayName)
    }

    @Test
    fun `rejects blank and control characters`() {
        val service = service(ProfileUserAccountStore())

        val blank = assertFailsWith<ApplicationException> {
            service.updateDisplayName("user-a", "   ")
        }
        val control = assertFailsWith<ApplicationException> {
            service.updateDisplayName("user-a", "New\nName")
        }

        assertEquals(ApplicationError.INVALID_ARGUMENT, blank.error)
        assertEquals(ApplicationError.INVALID_ARGUMENT, control.error)
    }

    private fun service(accounts: UserAccountStore) = ProfileService(
        userAccountReader = accounts,
        userProfileStore = accounts,
        transaction = object : ApplicationTransaction {
            override fun <T> execute(block: () -> T): T = block()
        },
    )
}

private class ProfileUserAccountStore : UserAccountStore {
    var user = UserAccountRecord(
        userId = "user-a",
        username = "user-a",
        passwordHash = "hash",
        displayName = "User A",
        avatarUrl = "https://storage/a.png",
    )

    override fun findByUsername(username: String): UserAccountRecord? = user.takeIf { it.username == username }

    override fun findById(userId: String): UserAccountRecord? = user.takeIf { it.userId == userId }

    override fun replacePasswordHash(userId: String, expectedPasswordHash: String, newPasswordHash: String): Boolean = false

    override fun compareAndSetAvatar(
        userId: String,
        expectedVersion: Long,
        objectKey: String,
    ): Boolean = false

    override fun updateDisplayName(userId: String, displayName: String): Boolean {
        if (user.userId != userId) return false
        user = user.copy(displayName = displayName)
        return true
    }

    override fun findReferencedObjectKeys(candidates: Set<String>) = emptySet<String>()
}
