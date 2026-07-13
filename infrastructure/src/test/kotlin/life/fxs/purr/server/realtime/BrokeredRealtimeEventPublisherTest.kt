package life.fxs.purr.server.realtime

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink

class BrokeredRealtimeEventPublisherTest {
    @Test
    fun `event is routed to every subscribed server instance`() = runBlocking {
        val network = TestBrokerNetwork()
        val localA = RecordingPublisher()
        val localB = RecordingPublisher()
        val publisherA = BrokeredRealtimeEventPublisher(network.createClient(), localA)
        val publisherB = BrokeredRealtimeEventPublisher(network.createClient(), localB)
        val event = event("call-1")

        publisherA.publishToUser("user-b", event)

        val expected = DeliveredEvent("user-b", event)
        assertEquals(expected, localA.awaitNext())
        assertEquals(expected, localB.awaitNext())
        assertTrue(publisherA.isReady())
        assertTrue(publisherB.isReady())

        publisherA.close()
        publisherB.close()
    }

    @Test
    fun `malformed broker message is ignored and close rejects later delivery`() = runBlocking {
        val network = TestBrokerNetwork()
        val local = RecordingPublisher()
        val publisher = BrokeredRealtimeEventPublisher(network.createClient(), local)
        val validEvent = event("call-valid")

        network.broadcast("not-json")
        publisher.publishToUser("user-b", validEvent)

        assertEquals(DeliveredEvent("user-b", validEvent), local.awaitNext())
        publisher.close()
        network.broadcast(realtimeJson.encodeToString(RoutedRealtimeEvent("user-b", validEvent.toPayload())))

        assertNull(withTimeoutOrNull(NO_EVENT_TIMEOUT_MILLIS) { local.awaitNext() })
        assertFalse(publisher.isReady())
    }

    @Test
    fun `publication suspends for broker acknowledgement without occupying caller thread`() = runBlocking {
        val broker = ControllableBroker()
        val publisher = BrokeredRealtimeEventPublisher(broker, RecordingPublisher())
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { callerDispatcher ->
            withContext(callerDispatcher) {
                val publication = async { publisher.publishToUser("user-b", event("call-1")) }
                broker.publishStarted.await()

                assertFalse(publication.isCompleted)
                assertEquals(
                    "caller-thread-available",
                    withTimeout(ASSERTION_TIMEOUT_MILLIS) {
                        async { "caller-thread-available" }.await()
                    },
                )

                broker.acknowledgement.complete(Unit)
                publication.await()
            }
        }
        publisher.close()
    }

    @Test
    fun `broker failure is propagated to preserve outbox retry semantics`() = runBlocking {
        val broker = ControllableBroker()
        val publisher = BrokeredRealtimeEventPublisher(broker, RecordingPublisher())
        val expected = IllegalStateException("redis unavailable")
        broker.acknowledgement.completeExceptionally(expected)

        val actual = runCatching { publisher.publishToUser("user-b", event("call-1")) }.exceptionOrNull()

        assertEquals(expected.message, assertIs<IllegalStateException>(actual).message)
        publisher.close()
    }

    @Test
    fun `slow local delivery does not block broker callback and newest message is rejected on overflow`() = runBlocking {
        val network = TestBrokerNetwork()
        val firstDeliveryStarted = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val overflowRecoveryStarted = CompletableDeferred<Unit>()
        val local = RecordingPublisher {
            firstDeliveryStarted.complete(Unit)
            releaseDelivery.await()
        }
        val publisher = BrokeredRealtimeEventPublisher(
            broker = network.createClient(),
            localPublisher = local,
            inboundBufferCapacity = 1,
            onInboundOverflow = { overflowRecoveryStarted.complete(Unit) },
        )

        publisher.publishToUser("user-b", event("call-1"))
        firstDeliveryStarted.await()
        withTimeout(ASSERTION_TIMEOUT_MILLIS) {
            publisher.publishToUser("user-b", event("call-2"))
            publisher.publishToUser("user-b", event("call-3"))
        }
        releaseDelivery.complete(Unit)
        overflowRecoveryStarted.await()

        assertEquals("call-1", local.awaitNext().event.callId)
        assertEquals("call-2", local.awaitNext().event.callId)
        assertNull(withTimeoutOrNull(NO_EVENT_TIMEOUT_MILLIS) { local.awaitNext() })
        publisher.close()
    }

    private fun event(callId: String) = RealtimeEvent(
        type = RealtimeEvent.CALL_STARTED,
        callId = callId,
        pairId = "pair-1",
        callerUserId = "user-a",
        startedAtEpochMillis = 1L,
    )

    private companion object {
        const val ASSERTION_TIMEOUT_MILLIS = 1_000L
        const val NO_EVENT_TIMEOUT_MILLIS = 200L
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

    override suspend fun publish(message: String) {
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

private class ControllableBroker : RealtimeMessageBroker {
    val publishStarted = CompletableDeferred<Unit>()
    val acknowledgement = CompletableDeferred<Unit>()
    private var handler: ((String) -> Unit)? = null
    private var open = true

    override fun subscribe(handler: (String) -> Unit) {
        check(open)
        this.handler = handler
    }

    override suspend fun publish(message: String) {
        check(open)
        publishStarted.complete(Unit)
        acknowledgement.await()
    }

    override fun isReady(): Boolean = open

    override fun close() {
        open = false
        handler = null
    }
}

private class RecordingPublisher(
    private val beforeDelivery: suspend () -> Unit = {},
) : RealtimeEventSink {
    private val deliveries = Channel<DeliveredEvent>(Channel.UNLIMITED)

    override suspend fun publishToUser(userId: String, event: RealtimeEvent) {
        beforeDelivery()
        deliveries.send(DeliveredEvent(userId, event))
    }

    suspend fun awaitNext(): DeliveredEvent = deliveries.receive()
}

private data class DeliveredEvent(
    val userId: String,
    val event: RealtimeEvent,
)
