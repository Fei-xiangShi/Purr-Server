package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory
import org.mindrot.jbcrypt.BCrypt

class UserRepositoryTest {
    @Test
    fun `bootstrap insert preserves user data and avatar URLs follow current storage configuration`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:user-upsert-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val repository = UserRepository { key -> "https://initial.example.test/$key" }
            assertTrue(repository.insertIfAbsent("user-a", "seed-name", "seed-password", "Seed Name", null))
            val originalHash = assertNotNull(repository.findById("user-a")).passwordHash
            val replacementHash = BCrypt.hashpw("user-password", BCrypt.gensalt())
            assertTrue(repository.replacePasswordHash("user-a", originalHash, replacementHash))
            assertTrue(repository.updateDisplayName("user-a", "User Name"))
            assertTrue(
                repository.compareAndSetAvatar(
                    userId = "user-a",
                    expectedVersion = 0,
                    objectKey = "avatars/user-a/user.jpg",
                ),
            )

            assertTrue(repository.insertIfAbsent("user-a", "changed-seed", "changed-seed-password", "Changed Seed", null))

            val relocatedRepository = UserRepository { key -> "https://relocated.example.test/$key" }
            val user = assertNotNull(relocatedRepository.findById("user-a"))
            assertEquals("seed-name", user.username)
            assertEquals("User Name", user.displayName)
            assertEquals("https://relocated.example.test/avatars/user-a/user.jpg", user.avatarUrl)
            assertFalse(BCrypt.checkpw("seed-password", user.passwordHash))
            assertTrue(BCrypt.checkpw("user-password", user.passwordHash))
            assertEquals("user-a", assertNotNull(repository.findByUsername("seed-name")).userId)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `password replacement requires the expected hash`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:user-password-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val repository = UserRepository { key -> "https://example.test/$key" }
            repository.insertIfAbsent("user-a", "user-a", "old-password", "User A", null)
            val originalHash = assertNotNull(repository.findById("user-a")).passwordHash
            val replacementHash = BCrypt.hashpw("new-password", BCrypt.gensalt())

            assertFalse(repository.replacePasswordHash("user-a", "stale-hash", replacementHash))
            assertTrue(repository.replacePasswordHash("user-a", originalHash, replacementHash))
            assertTrue(BCrypt.checkpw("new-password", assertNotNull(repository.findById("user-a")).passwordHash))
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `profile fields can be updated independently`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:user-profile-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val repository = UserRepository { key -> "https://example.test/$key" }
            repository.insertIfAbsent("user-a", "user-a", "password", "Old Name", null)

            assertTrue(repository.updateDisplayName("user-a", "New Name"))
            assertTrue(
                repository.compareAndSetAvatar(
                    userId = "user-a",
                    expectedVersion = 0,
                    objectKey = "avatars/user-a/a.jpg",
                ),
            )
            val user = assertNotNull(repository.findById("user-a"))
            assertEquals("New Name", user.displayName)
            assertEquals("https://example.test/avatars/user-a/a.jpg", user.avatarUrl)
            assertEquals(1, user.avatarVersion)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `avatar replacement uses optimistic version compare and set`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:user-avatar-cas-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val repository = UserRepository { key -> "https://cdn.example/$key" }
            repository.insertIfAbsent("user-a", "user-a", "password", "User A", null)

            assertTrue(repository.compareAndSetAvatar("user-a", 0, "avatars/user-a/a.jpg"))
            assertFalse(repository.compareAndSetAvatar("user-a", 0, "avatars/user-a/b.jpg"))
            val user = assertNotNull(repository.findById("user-a"))
            assertEquals("avatars/user-a/a.jpg", user.avatarObjectKey)
            assertEquals(1, user.avatarVersion)
            assertEquals("https://cdn.example/avatars/user-a/a.jpg", user.avatarUrl)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }
}
