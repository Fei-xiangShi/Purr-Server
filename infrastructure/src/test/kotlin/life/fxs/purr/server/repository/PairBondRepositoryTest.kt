package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory

class PairBondRepositoryTest {
    @Test
    fun `bootstrap insert preserves an existing pair on restart`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:pair-upsert-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val users = UserRepository()
            users.insertIfAbsent("user-a", "user-a", "pass-a", "A", null)
            users.insertIfAbsent("user-b", "user-b", "pass-b", "B", null)
            users.insertIfAbsent("new-a", "new-a", "pass-new-a", "New A", null)
            users.insertIfAbsent("new-b", "new-b", "pass-new-b", "New B", null)
            val repository = PairBondRepository()
            repository.insertIfAbsent("pair-1", "user-a", "user-b", 1L)
            repository.insertIfAbsent("pair-1", "new-a", "new-b", 2L)

            val pair = assertNotNull(repository.findByPairId("pair-1"))
            assertEquals("user-a", pair.userAId)
            assertEquals("user-b", pair.userBId)
            assertEquals(1L, pair.bondedAtEpochMillis)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }
}
