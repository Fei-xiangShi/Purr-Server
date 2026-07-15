package life.fxs.purr.server.push

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.application.port.IncomingCallPushMessage
import life.fxs.purr.server.application.port.PushDeliveryResult
import life.fxs.purr.server.application.port.PushDeviceRecord
import life.fxs.purr.server.application.port.PushDeviceStore
import life.fxs.purr.server.application.port.PushNotificationSender
import life.fxs.purr.server.application.port.PushProvider
import life.fxs.purr.server.application.port.RealtimeEvent

class IncomingCallPushEventSinkTest {
    @Test
    fun `call started is delivered to every active device`() = runBlocking {
        val store = FakeStore(mutableListOf(device("token-a"), device("token-b")))
        val delivered = mutableListOf<String>()
        val sink = IncomingCallPushEventSink(
            deviceStore = store,
            sender = PushNotificationSender { device, _ ->
                delivered += device.token
                PushDeliveryResult.Delivered
            },
            enabled = true,
        )

        sink.publishToUser("user-b", callStarted())

        assertEquals(listOf("token-a", "token-b"), delivered)
    }

    @Test
    fun `unregistered device is disabled`() = runBlocking {
        val store = FakeStore(mutableListOf(device("token-a")))
        val sink = IncomingCallPushEventSink(
            deviceStore = store,
            sender = PushNotificationSender { _, _ -> PushDeliveryResult.DeviceUnregistered },
            enabled = true,
            nowProvider = { NOW },
        )

        sink.publishToUser("user-b", callStarted())

        assertEquals(listOf("token-a" to NOW.toEpochMilli()), store.disabled)
    }

    @Test
    fun `one transient failure does not prevent other devices from being attempted`() = runBlocking {
        val store = FakeStore(mutableListOf(device("token-a"), device("token-b")))
        val attempted = mutableListOf<String>()
        val sink = IncomingCallPushEventSink(
            deviceStore = store,
            sender = PushNotificationSender { device, _ ->
                attempted += device.token
                if (device.token == "token-a") error("temporary failure")
                PushDeliveryResult.Delivered
            },
            enabled = true,
        )

        assertFailsWith<IllegalStateException> {
            sink.publishToUser("user-b", callStarted())
        }

        assertEquals(listOf("token-a", "token-b"), attempted)
    }

    @Test
    fun `non call event does not query device storage`() = runBlocking {
        val store = FakeStore(mutableListOf(device("token-a")))
        val sink = IncomingCallPushEventSink(
            deviceStore = store,
            sender = PushNotificationSender { _, _ -> error("must not send") },
            enabled = true,
        )

        sink.publishToUser("user-b", RealtimeEvent(type = RealtimeEvent.PRESENCE_CHANGED))

        assertTrue(store.queries == 0)
    }

    @Test
    fun `cancellation stops device delivery immediately and is not wrapped`() = runBlocking {
        val store = FakeStore(mutableListOf(device("token-a"), device("token-b")))
        val cancellation = CancellationException("cancelled")
        val attempted = mutableListOf<String>()
        val sink = IncomingCallPushEventSink(
            deviceStore = store,
            sender = PushNotificationSender { device, _ ->
                attempted += device.token
                throw cancellation
            },
            enabled = true,
        )

        val thrown = assertFailsWith<CancellationException> {
            sink.publishToUser("user-b", callStarted())
        }

        assertEquals(cancellation, thrown)
        assertEquals(listOf("token-a"), attempted)
    }

    private class FakeStore(
        val devices: MutableList<PushDeviceRecord>,
    ) : PushDeviceStore {
        val disabled = mutableListOf<Pair<String, Long>>()
        var queries = 0

        override fun upsert(device: PushDeviceRecord) = Unit

        override fun remove(userId: String, installationId: String) = false

        override fun findActiveByUserId(userId: String): List<PushDeviceRecord> {
            queries++
            return devices.filter { it.userId == userId }
        }

        override fun disable(provider: PushProvider, token: String, disabledAtEpochMillis: Long): Boolean {
            disabled += token to disabledAtEpochMillis
            return true
        }
    }

    private fun device(token: String) = PushDeviceRecord(
        installationId = "installation-$token",
        userId = "user-b",
        sessionId = "session-b",
        provider = PushProvider.FCM,
        token = token,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun callStarted() = RealtimeEvent(
        type = RealtimeEvent.CALL_STARTED,
        callId = "call-1",
        pairId = "pair-1",
        callerUserId = "user-a",
        startedAtEpochMillis = NOW.toEpochMilli(),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T12:00:00Z")
    }
}
