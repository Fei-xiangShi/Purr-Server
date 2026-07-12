package life.fxs.purr.server.application.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.AvatarObjectStore
import life.fxs.purr.server.application.port.StoredAvatar
import life.fxs.purr.server.application.port.UserAccountRecord
import life.fxs.purr.server.application.port.UserAccountStore

class AvatarServiceTest {
    @Test
    fun `validates image signature before uploading`() {
        val store = FakeAvatarObjectStore()
        val service = service(FakeAvatarUserAccountStore(), store)

        val exception = assertFailsWith<ApplicationException> {
            service.updateAvatar("user-a", "image/png", byteArrayOf(1, 2, 3))
        }

        assertEquals(ApplicationError.INVALID_ARGUMENT, exception.error)
        assertTrue(store.uploads.isEmpty())
    }

    @Test
    fun `uploads avatar and updates profile`() {
        val accounts = FakeAvatarUserAccountStore(oldAvatarUrl = "https://storage/avatars/old.png")
        val store = FakeAvatarObjectStore()
        val service = service(accounts, store)

        val profile = service.updateAvatar("user-a", "image/png", pngBytes())

        assertEquals("https://storage/avatars/new.png", profile.avatarUrl)
        assertEquals("https://storage/avatars/new.png", accounts.user.avatarUrl)
        assertEquals(1, store.uploads.size)
        assertEquals(listOf("https://storage/avatars/old.png"), store.deletedUrls)
    }

    @Test
    fun `cleans up uploaded object when profile update fails`() {
        val accounts = FakeAvatarUserAccountStore(updateSucceeds = false)
        val store = FakeAvatarObjectStore()
        val service = service(accounts, store)

        val exception = assertFailsWith<ApplicationException> {
            service.updateAvatar("user-a", "image/jpeg", jpegBytes())
        }

        assertEquals(ApplicationError.CONFLICT, exception.error)
        assertEquals(listOf("https://storage/avatars/new.png"), store.deletedUrls)
    }

    @Test
    fun `rejects avatars above size limit`() {
        val store = FakeAvatarObjectStore()
        val service = service(FakeAvatarUserAccountStore(), store)
        val bytes = ByteArray(10 * 1024 * 1024 + 1).also {
            it[0] = 0x89.toByte()
            it[1] = 0x50
            it[2] = 0x4E
            it[3] = 0x47
            it[4] = 0x0D
            it[5] = 0x0A
            it[6] = 0x1A
            it[7] = 0x0A
        }

        val exception = assertFailsWith<ApplicationException> {
            service.updateAvatar("user-a", "image/png", bytes)
        }

        assertEquals(ApplicationError.INVALID_ARGUMENT, exception.error)
        assertTrue(store.uploads.isEmpty())
    }

    private fun service(accounts: UserAccountStore, store: AvatarObjectStore) = AvatarService(
        userAccountStore = accounts,
        avatarObjectStore = store,
        transaction = object : ApplicationTransaction {
            override fun <T> execute(block: () -> T): T = block()
        },
    )

    private fun pngBytes() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun jpegBytes() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
}

private class FakeAvatarObjectStore : AvatarObjectStore {
    val uploads = mutableListOf<ByteArray>()
    val deletedUrls = mutableListOf<String>()

    override fun put(userId: String, contentType: String, bytes: ByteArray): StoredAvatar {
        uploads += bytes
        return StoredAvatar("https://storage/avatars/new.png")
    }

    override fun deleteByUrl(url: String) {
        deletedUrls += url
    }
}

private class FakeAvatarUserAccountStore(
    private val oldAvatarUrl: String? = null,
    private val updateSucceeds: Boolean = true,
) : UserAccountStore {
    var user = UserAccountRecord(
        userId = "user-a",
        username = "user-a",
        passwordHash = "hash",
        displayName = "User A",
        avatarUrl = oldAvatarUrl,
    )

    override fun findByUsername(username: String): UserAccountRecord? = user.takeIf { it.username == username }

    override fun findById(userId: String): UserAccountRecord? = user.takeIf { it.userId == userId }

    override fun replacePasswordHash(userId: String, expectedPasswordHash: String, newPasswordHash: String): Boolean = false

    override fun updateAvatarUrl(userId: String, avatarUrl: String): Boolean {
        if (!updateSucceeds || user.userId != userId) return false
        user = user.copy(avatarUrl = avatarUrl)
        return true
    }

    override fun updateDisplayName(userId: String, displayName: String): Boolean = false
}
