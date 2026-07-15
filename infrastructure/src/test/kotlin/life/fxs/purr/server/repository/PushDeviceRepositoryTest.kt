package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import life.fxs.purr.server.application.port.PushDeviceRecord
import life.fxs.purr.server.application.port.PushProvider
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory

class PushDeviceRepositoryTest {
    @Test
    fun `registration is idempotent and session deletion removes the device`() = withDatabase {
        val users = UserRepository()
        users.insertIfAbsent("user-a", "user-a", "password", "User A", null)
        val sessions = AuthSessionRepository()
        val session = sessions.create("user-a", "refresh-token", 1L, 10_000L)
        val repository = PushDeviceRepository()

        repository.upsert(device(session.sessionId, token = TOKEN_A, updatedAt = 2L))
        repository.upsert(device(session.sessionId, token = TOKEN_B, updatedAt = 3L))

        val registered = repository.findActiveByUserId("user-a").single()
        assertEquals(TOKEN_B, registered.token)
        assertEquals(3L, registered.updatedAtEpochMillis)

        sessions.deleteBySessionId(session.sessionId)

        assertTrue(repository.findActiveByUserId("user-a").isEmpty())
    }

    @Test
    fun `unregistered token is disabled without deleting installation history`() = withDatabase {
        val users = UserRepository()
        users.insertIfAbsent("user-a", "user-a", "password", "User A", null)
        val session = AuthSessionRepository().create("user-a", "refresh-token", 1L, 10_000L)
        val repository = PushDeviceRepository()
        repository.upsert(device(session.sessionId, TOKEN_A, 2L))

        assertTrue(repository.disable(PushProvider.FCM, TOKEN_A, 4L))

        assertTrue(repository.findActiveByUserId("user-a").isEmpty())
    }

    private fun device(sessionId: String, token: String, updatedAt: Long) = PushDeviceRecord(
        installationId = INSTALLATION_ID,
        userId = "user-a",
        sessionId = sessionId,
        provider = PushProvider.FCM,
        token = token,
        createdAtEpochMillis = 2L,
        updatedAtEpochMillis = updatedAt,
    )

    private fun withDatabase(block: () -> Unit) {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:push-device-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()
        try {
            block()
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private companion object {
        const val INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val TOKEN_A = "fcm-token-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TOKEN_B = "fcm-token-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
