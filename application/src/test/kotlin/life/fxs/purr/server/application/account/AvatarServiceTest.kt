package life.fxs.purr.server.application.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.AvatarCleanupQueue
import life.fxs.purr.server.application.port.AvatarImageProcessor
import life.fxs.purr.server.application.port.AvatarImageRejectedException
import life.fxs.purr.server.application.port.AvatarImageProcessingUnavailableException
import life.fxs.purr.server.application.port.AvatarObjectDeleter
import life.fxs.purr.server.application.port.AvatarObjectUploader
import life.fxs.purr.server.application.port.ProcessedAvatar
import life.fxs.purr.server.application.port.StoredAvatar
import life.fxs.purr.server.application.port.UserAccountRecord
import life.fxs.purr.server.application.port.UserAccountStore

class AvatarServiceTest {
    @Test
    fun `rejects undecodable images before object upload`() {
        val store = FakeAvatarObjectStore()
        val service = service(
            accounts = FakeAvatarUserAccountStore(),
            store = store,
            processor = AvatarImageProcessor { _, _ -> throw AvatarImageRejectedException("malformed") },
        )

        val exception = assertFailsWith<ApplicationException> {
            service.updateAvatar("user-a", "image/png", byteArrayOf(1, 2, 3))
        }

        assertEquals(ApplicationError.INVALID_ARGUMENT, exception.error)
        assertTrue(store.uploads.isEmpty())
    }

    @Test
    fun `reports saturated image processing as temporarily unavailable`() {
        val service = service(
            accounts = FakeAvatarUserAccountStore(),
            store = FakeAvatarObjectStore(),
            processor = AvatarImageProcessor { _, _ ->
                throw AvatarImageProcessingUnavailableException("capacity exhausted")
            },
        )

        val exception = assertFailsWith<ApplicationException> {
            service.updateAvatar("user-a", "image/png", byteArrayOf(1))
        }

        assertEquals(ApplicationError.TEMPORARILY_UNAVAILABLE, exception.error)
    }

    @Test
    fun `stores processed avatar and durably queues old object`() {
        val accounts = FakeAvatarUserAccountStore(oldObjectKey = OLD_KEY, avatarVersion = 4)
        val store = FakeAvatarObjectStore()
        val cleanup = FakeCleanupQueue()
        val service = service(accounts, store, cleanup = cleanup)

        val profile = service.updateAvatar("user-a", "image/png", byteArrayOf(1, 2, 3))

        assertEquals("https://storage/$NEW_KEY", profile.avatarUrl)
        assertEquals(NEW_KEY, accounts.user.avatarObjectKey)
        assertEquals(5, accounts.user.avatarVersion)
        assertEquals(listOf("image/jpeg"), store.uploadContentTypes)
        assertEquals(listOf(OLD_KEY), cleanup.keys)
    }

    @Test
    fun `concurrent profile change queues newly uploaded object and returns conflict`() {
        val accounts = FakeAvatarUserAccountStore(updateSucceeds = false)
        val store = FakeAvatarObjectStore()
        val cleanup = FakeCleanupQueue()
        val service = service(accounts, store, cleanup = cleanup)

        val exception = assertFailsWith<ApplicationException> {
            service.updateAvatar("user-a", "image/jpeg", byteArrayOf(1, 2, 3))
        }

        assertEquals(ApplicationError.CONFLICT, exception.error)
        assertEquals(listOf(NEW_KEY), cleanup.keys)
    }

    @Test
    fun `database failure falls back to durable cleanup when immediate delete fails`() {
        val store = FakeAvatarObjectStore(deleteFails = true)
        val cleanup = FakeCleanupQueue()
        val service = service(
            accounts = FakeAvatarUserAccountStore(),
            store = store,
            cleanup = cleanup,
            transaction = object : ApplicationTransaction {
                override fun <T> execute(block: () -> T): T = error("database unavailable")
            },
        )

        assertFailsWith<IllegalStateException> {
            service.updateAvatar("user-a", "image/jpeg", byteArrayOf(1))
        }

        assertEquals(listOf(NEW_KEY), store.deletedKeys)
        assertEquals(listOf(NEW_KEY), cleanup.keys)
    }

    @Test
    fun `indeterminate commit preserves an avatar that became the current reference`() {
        val accounts = FakeAvatarUserAccountStore()
        val store = FakeAvatarObjectStore()
        val service = service(
            accounts = accounts,
            store = store,
            transaction = object : ApplicationTransaction {
                override fun <T> execute(block: () -> T): T {
                    block()
                    error("commit acknowledgement was lost")
                }
            },
        )

        val profile = service.updateAvatar("user-a", "image/jpeg", byteArrayOf(1))

        assertEquals("https://storage/$NEW_KEY", profile.avatarUrl)
        assertTrue(store.deletedKeys.isEmpty())
    }

    @Test
    fun `rejects avatars above size limit before processing`() {
        var processed = false
        val service = service(
            accounts = FakeAvatarUserAccountStore(),
            store = FakeAvatarObjectStore(),
            processor = AvatarImageProcessor { _, _ ->
                processed = true
                processedAvatar()
            },
        )

        val exception = assertFailsWith<ApplicationException> {
            service.updateAvatar("user-a", "image/png", ByteArray(10 * 1024 * 1024 + 1))
        }

        assertEquals(ApplicationError.INVALID_ARGUMENT, exception.error)
        assertTrue(!processed)
    }

    private fun service(
        accounts: UserAccountStore,
        store: FakeAvatarObjectStore,
        processor: AvatarImageProcessor = AvatarImageProcessor { _, _ -> processedAvatar() },
        cleanup: AvatarCleanupQueue = FakeCleanupQueue(),
        transaction: ApplicationTransaction = object : ApplicationTransaction {
            override fun <T> execute(block: () -> T): T = block()
        },
    ) = AvatarService(
        userAccountReader = accounts,
        userProfileStore = accounts,
        imageProcessor = processor,
        avatarObjectUploader = store,
        avatarObjectDeleter = store,
        cleanupQueue = cleanup,
        transaction = transaction,
    )

    companion object {
        const val OLD_KEY = "avatars/user-a/00000000-0000-0000-0000-000000000001.jpg"
        const val NEW_KEY = "avatars/user-a/00000000-0000-0000-0000-000000000002.jpg"

        fun processedAvatar() = ProcessedAvatar(
            contentType = "image/jpeg",
            bytes = byteArrayOf(4, 5, 6),
            width = 512,
            height = 512,
        )
    }
}

private class FakeAvatarObjectStore(
    private val deleteFails: Boolean = false,
) : AvatarObjectUploader, AvatarObjectDeleter {
    val uploads = mutableListOf<ByteArray>()
    val uploadContentTypes = mutableListOf<String>()
    val deletedKeys = mutableListOf<String>()

    override fun put(userId: String, contentType: String, bytes: ByteArray): StoredAvatar {
        uploads += bytes
        uploadContentTypes += contentType
        return StoredAvatar(AvatarServiceTest.NEW_KEY)
    }

    override fun delete(objectKey: String) {
        deletedKeys += objectKey
        if (deleteFails) error("storage unavailable")
    }

}

private class FakeCleanupQueue : AvatarCleanupQueue {
    val keys = mutableListOf<String>()

    override fun enqueue(objectKey: String, nowEpochMillis: Long) {
        keys += objectKey
    }
}

private class FakeAvatarUserAccountStore(
    oldObjectKey: String? = null,
    avatarVersion: Long = 0,
    private val updateSucceeds: Boolean = true,
) : UserAccountStore {
    var user = UserAccountRecord(
        userId = "user-a",
        username = "user-a",
        passwordHash = "hash",
        displayName = "User A",
        avatarUrl = oldObjectKey?.let { "https://storage/$it" },
        avatarObjectKey = oldObjectKey,
        avatarVersion = avatarVersion,
    )

    override fun findByUsername(username: String): UserAccountRecord? = user.takeIf { it.username == username }

    override fun findById(userId: String): UserAccountRecord? = user.takeIf { it.userId == userId }

    override fun replacePasswordHash(userId: String, expectedPasswordHash: String, newPasswordHash: String) = false

    override fun compareAndSetAvatar(
        userId: String,
        expectedVersion: Long,
        objectKey: String,
    ): Boolean {
        if (!updateSucceeds || user.userId != userId || user.avatarVersion != expectedVersion) return false
        user = user.copy(
            avatarUrl = "https://storage/$objectKey",
            avatarObjectKey = objectKey,
            avatarVersion = expectedVersion + 1,
        )
        return true
    }

    override fun updateDisplayName(userId: String, displayName: String) = false

    override fun findReferencedObjectKeys(candidates: Set<String>) = candidates.intersect(setOfNotNull(user.avatarObjectKey))
}
