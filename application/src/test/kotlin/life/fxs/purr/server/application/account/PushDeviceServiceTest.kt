package life.fxs.purr.server.application.account

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.PushDeviceRecord
import life.fxs.purr.server.application.port.PushDeviceStore
import life.fxs.purr.server.application.port.PushProvider

class PushDeviceServiceTest {
    private val store = FakePushDeviceStore()
    private val service = PushDeviceService(store) { NOW }

    @Test
    fun `registration writes a session scoped device`() {
        service.register(
            userId = "user-a",
            sessionId = "session-a",
            installationId = INSTALLATION_ID,
            provider = PushProvider.FCM,
            token = TOKEN,
        )

        assertEquals(
            PushDeviceRecord(
                installationId = INSTALLATION_ID,
                userId = "user-a",
                sessionId = "session-a",
                provider = PushProvider.FCM,
                token = TOKEN,
                createdAtEpochMillis = NOW.toEpochMilli(),
                updatedAtEpochMillis = NOW.toEpochMilli(),
            ),
            store.devices.single(),
        )
    }

    @Test
    fun `invalid installation and token are rejected before persistence`() {
        assertFailsWith<ApplicationException> {
            service.register("user-a", "session-a", "short", PushProvider.FCM, TOKEN)
        }
        assertFailsWith<ApplicationException> {
            service.register("user-a", "session-a", INSTALLATION_ID, PushProvider.FCM, "short")
        }

        assertTrue(store.devices.isEmpty())
    }

    @Test
    fun `FCM installation identifiers are accepted as push addresses`() {
        service.register(
            userId = "user-a",
            sessionId = "session-a",
            installationId = INSTALLATION_ID,
            provider = PushProvider.FCM,
            token = "cdefghijklmnopqrstuvwx",
        )

        assertEquals("cdefghijklmnopqrstuvwx", store.devices.single().token)
    }

    private class FakePushDeviceStore : PushDeviceStore {
        val devices = mutableListOf<PushDeviceRecord>()

        override fun upsert(device: PushDeviceRecord) {
            devices.removeAll { it.installationId == device.installationId }
            devices += device
        }

        override fun remove(userId: String, installationId: String): Boolean =
            devices.removeIf { it.userId == userId && it.installationId == installationId }

        override fun findActiveByUserId(userId: String) = devices.filter { it.userId == userId }

        override fun disable(provider: PushProvider, token: String, disabledAtEpochMillis: Long) = false
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T12:00:00Z")
        const val INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val TOKEN = "fcm-token-abcdefghijklmnopqrstuvwxyz-0123456789"
    }
}
