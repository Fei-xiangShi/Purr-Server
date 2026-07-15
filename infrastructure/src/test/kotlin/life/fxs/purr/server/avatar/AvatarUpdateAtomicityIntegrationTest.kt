package life.fxs.purr.server.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import life.fxs.purr.server.application.account.AvatarService
import life.fxs.purr.server.application.port.AvatarCleanupQueue
import life.fxs.purr.server.application.port.AvatarImageProcessor
import life.fxs.purr.server.application.port.AvatarObjectDeleter
import life.fxs.purr.server.application.port.AvatarObjectUploader
import life.fxs.purr.server.application.port.ProcessedAvatar
import life.fxs.purr.server.application.port.StoredAvatar
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.repository.UserRepository

class AvatarUpdateAtomicityIntegrationTest {
    @Test
    fun `cleanup enqueue failure rolls back the avatar reference and compensates the upload`() {
        val resources = DatabaseFactory(databaseConfig()).connect()
        try {
            val users = seededUserRepository()
            val objects = FakeAvatarObjects()
            val service = avatarService(
                users = users,
                objects = objects,
                cleanupQueue = object : AvatarCleanupQueue {
                    override fun enqueue(objectKey: String, nowEpochMillis: Long) {
                        error("cleanup database write failed")
                    }
                },
                transaction = resources.applicationTransaction,
            )

            assertFailsWith<IllegalStateException> {
                service.updateAvatar("user-a", "image/jpeg", byteArrayOf(1))
            }

            val user = assertNotNull(users.findById("user-a"))
            assertEquals(OLD_KEY, user.avatarObjectKey)
            assertEquals(1, user.avatarVersion)
            assertEquals(listOf(NEW_KEY), objects.deletedKeys)
        } finally {
            resources.close()
        }
    }

    @Test
    fun `avatar reference and old object cleanup task commit together`() {
        val resources = DatabaseFactory(databaseConfig()).connect()
        try {
            val users = seededUserRepository()
            val objects = FakeAvatarObjects()
            val cleanupTasks = AvatarCleanupRepository()
            val service = avatarService(
                users = users,
                objects = objects,
                cleanupQueue = cleanupTasks,
                transaction = resources.applicationTransaction,
            )

            service.updateAvatar("user-a", "image/jpeg", byteArrayOf(1))

            val user = assertNotNull(users.findById("user-a"))
            assertEquals(NEW_KEY, user.avatarObjectKey)
            assertEquals(2, user.avatarVersion)
            assertNotNull(cleanupTasks.find(OLD_KEY))
        } finally {
            resources.close()
        }
    }

    private fun seededUserRepository(): UserRepository {
        val users = UserRepository { key -> "https://avatars.example/$key" }
        users.insertIfAbsent("user-a", "user-a", "password", "User A", null)
        check(users.compareAndSetAvatar("user-a", 0, OLD_KEY))
        return users
    }

    private fun avatarService(
        users: UserRepository,
        objects: FakeAvatarObjects,
        cleanupQueue: AvatarCleanupQueue,
        transaction: life.fxs.purr.server.application.port.ApplicationTransaction,
    ) = AvatarService(
        userAccountReader = users,
        userProfileStore = users,
        imageProcessor = AvatarImageProcessor { _, _ ->
            ProcessedAvatar("image/jpeg", byteArrayOf(2), 512, 512)
        },
        avatarObjectUploader = objects,
        avatarObjectDeleter = objects,
        cleanupQueue = cleanupQueue,
        transaction = transaction,
    )

    private fun databaseConfig() = DatabaseConfig(
        jdbcUrl = "jdbc:h2:mem:avatar-atomicity-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        driverClassName = "org.h2.Driver",
        username = "sa",
        password = "",
        maximumPoolSize = 2,
    )

}

private class FakeAvatarObjects : AvatarObjectUploader, AvatarObjectDeleter {
    val deletedKeys = mutableListOf<String>()

    override fun put(userId: String, contentType: String, bytes: ByteArray): StoredAvatar =
        StoredAvatar(NEW_KEY)

    override fun delete(objectKey: String) {
        deletedKeys += objectKey
    }
}

private const val OLD_KEY = "avatars/user-a/00000000-0000-0000-0000-000000000001.jpg"
private const val NEW_KEY = "avatars/user-a/00000000-0000-0000-0000-000000000002.jpg"
