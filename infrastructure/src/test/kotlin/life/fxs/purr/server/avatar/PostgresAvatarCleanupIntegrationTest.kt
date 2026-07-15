package life.fxs.purr.server.avatar

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory

class PostgresAvatarCleanupIntegrationTest {
    @Test
    fun `postgres migrations apply and concurrent workers claim different objects`() {
        EmbeddedPostgres.start().use { postgres ->
            val jdbcUrl = postgres.postgresDatabase.connection.use { it.metaData.url }
            val resources = DatabaseFactory(
                DatabaseConfig(
                    jdbcUrl = jdbcUrl,
                    driverClassName = "org.postgresql.Driver",
                    username = "postgres",
                    password = "postgres",
                    maximumPoolSize = 4,
                ),
            ).connect()
            try {
                val repository = AvatarCleanupRepository()
                repository.enqueue(OBJECT_A, 1_000)
                repository.enqueue(OBJECT_B, 1_000)
                val barrier = CyclicBarrier(2)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val claims = listOf("worker-a", "worker-b").map { workerId ->
                        executor.submit<String> {
                            barrier.await()
                            assertNotNull(
                                repository.claimNext(
                                    workerId = workerId,
                                    nowEpochMillis = 1_000,
                                    leaseUntilEpochMillis = 61_000,
                                ),
                            ).objectKey
                        }
                    }.map { it.get() }

                    assertEquals(setOf(OBJECT_A, OBJECT_B), claims.toSet())
                } finally {
                    executor.shutdownNow()
                }
            } finally {
                resources.close()
            }
        }
    }

    private companion object {
        const val OBJECT_A = "avatars/user-a/00000000-0000-0000-0000-000000000001.jpg"
        const val OBJECT_B = "avatars/user-b/00000000-0000-0000-0000-000000000002.jpg"
    }
}
