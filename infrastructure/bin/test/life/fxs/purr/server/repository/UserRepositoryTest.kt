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
    fun `upsert rotates credentials and profile without duplicating user`() {
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
            val repository = UserRepository()
            repository.upsert("user-a", "old-name", "old-password", "Old Name", null)
            repository.upsert("user-a", "new-name", "new-password", "New Name", "https://example.test/a.png")

            val user = assertNotNull(repository.findById("user-a"))
            assertEquals("new-name", user.username)
            assertEquals("New Name", user.displayName)
            assertEquals("https://example.test/a.png", user.avatarUrl)
            assertFalse(BCrypt.checkpw("old-password", user.passwordHash))
            assertTrue(BCrypt.checkpw("new-password", user.passwordHash))
            assertEquals("user-a", assertNotNull(repository.findByUsername("new-name")).userId)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }
}
