package life.fxs.purr.server.realtime

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink

class BrokeredRealtimeEventPublisherTest {
    @Test
    fun `event is routed to every subscribed server instance`() {
        val network = TestBrokerNetwork()
        val localA = RecordingPublisher()
        val localB = RecordingPublisher()
        val publisherA = BrokeredRealtimeEventPublisher(network.createClient(), localA)
        val publisherB = BrokeredRealtimeEventPublisher(network.createClient(), localB)
        val event = RealtimeEvent(
            type = RealtimeEvent.CALL_STARTED,
            callId = "call-1",
            pairId = "pair-1",
            callerUserId = "user-a",
            startedAtEpochMillis = 1L,
        )

        publisherA.publishToUser("user-b", event)

        assertEquals(listOf(DeliveredEvent("user-b", event)), localA.events)
        assertEquals(listOf(DeliveredEvent("user-b", event)), localB.events)
        assertTrue(publisherA.isReady())
        assertTrue(publisherB.isReady())

        publisherA.close()
        publisherB.close()
    }

    @Test
    fun `malformed broker message is ignored and closed publisher is not ready`() {
        val network = TestBrokerNetwork()
        val local = RecordingPublisher()
        val publisher = BrokeredRealtimeEventPublisher(network.createClient(), local)

        network.broadcast("not-json")
        publisher.close()

        assertTrue(local.events.isEmpty())
        assertFalse(publisher.isReady())
    }
}

private class TestBrokerNetwork {
    private val clients = CopyOnWriteArrayList<TestBrokerClient>()

    fun createClient(): RealtimeMessageBroker = TestBrokerClient(this).also(clients::add)

    fun broadcast(message: String) {
        clients.forEach { it.deliver(message) }
    }

    fun remove(client: TestBrokerClient) {
        clients.remove(client)
    }
}

private class TestBrokerClient(
    private val network: TestBrokerNetwork,
) : RealtimeMessageBroker {
    private var handler: ((String) -> Unit)? = null
    private var open = true

    override fun subscribe(handler: (String) -> Unit) {
        check(open)
        this.handler = handler
    }

    override fun publish(message: String) {
        check(open)
        network.broadcast(message)
    }

    override fun isReady(): Boolean = open

    override fun close() {
        if (!open) return
        open = false
        handler = null
        network.remove(this)
    }

    fun deliver(message: String) {
        if (open) handler?.invoke(message)
    }
}

private class RecordingPublisher : RealtimeEventSink {
    val events = mutableListOf<DeliveredEvent>()

    override fun publishToUser(userId: String, event: RealtimeEvent) {
        events += DeliveredEvent(userId, event)
    }
}

private data class DeliveredEvent(
    val userId: String,
    val event: RealtimeEvent,
)
