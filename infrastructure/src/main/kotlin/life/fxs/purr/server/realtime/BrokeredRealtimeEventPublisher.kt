@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package life.fxs.purr.server.realtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink
import org.slf4j.LoggerFactory

class BrokeredRealtimeEventPublisher(
    private val broker: RealtimeMessageBroker,
    private val localPublisher: RealtimeEventSink,
    private val json: Json = realtimeJson,
    inboundBufferCapacity: Int = DEFAULT_INBOUND_BUFFER_CAPACITY,
    private val onInboundOverflow: suspend () -> Unit = {},
) : RealtimeEventSink, AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val droppedInboundMessages = AtomicLong(0)
    private val overflowRecoveryTriggered = AtomicBoolean(false)

    init {
        require(inboundBufferCapacity > 0) { "Inbound realtime buffer capacity must be positive" }
    }

    private val inboundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inboundMessages = Channel<String>(
        capacity = inboundBufferCapacity,
        // Redis callbacks must never suspend its event loop. trySend below
        // therefore rejects the newest message when this bounded buffer is full.
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val inboundJob = inboundScope.launch {
        for (message in inboundMessages) {
            handleMessage(message)
        }
    }

    init {
        try {
            broker.subscribe(::enqueueInboundMessage)
        } catch (error: Throwable) {
            inboundMessages.cancel()
            inboundScope.cancel()
            runCatching { broker.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    override suspend fun publishToUser(userId: String, event: RealtimeEvent) {
        check(!closed.get()) { "Realtime event publisher is closed" }
        broker.publish(json.encodeToString(RoutedRealtimeEvent(userId, event.toPayload())))
    }

    override fun isReady(): Boolean = !closed.get() && inboundJob.isActive && broker.isReady()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            broker.close()
        } catch (error: Throwable) {
            failure = error
        } finally {
            // Realtime delivery is ephemeral. Once subscriptions are closed,
            // discard queued messages so shutdown cannot wait on a slow client.
            inboundMessages.cancel()
            inboundScope.cancel()
        }
        failure?.let { throw it }
    }

    private fun enqueueInboundMessage(message: String) {
        if (closed.get()) return
        if (inboundMessages.trySend(message).isFailure && !closed.get()) {
            val droppedCount = droppedInboundMessages.incrementAndGet()
            if (droppedCount == 1L || droppedCount % DROP_LOG_INTERVAL == 0L) {
                logger.error(
                    "Dropped realtime broker message because the inbound buffer is full; total dropped={}",
                    droppedCount,
                )
            }
            if (overflowRecoveryTriggered.compareAndSet(false, true)) {
                // Pub/Sub is intentionally ephemeral. Force local sockets to
                // reconnect so the server's snapshot route can reconcile any
                // event lost while this bounded hand-off queue was saturated.
                inboundScope.launch {
                    try {
                        onInboundOverflow()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logger.error("Realtime overflow recovery failed", error)
                    } finally {
                        delay(OVERFLOW_RECOVERY_COOLDOWN_MILLIS)
                        overflowRecoveryTriggered.set(false)
                    }
                }
            }
        }
    }

    private suspend fun handleMessage(message: String) {
        val routedEvent = runCatching { json.decodeFromString<RoutedRealtimeEvent>(message) }
            .onFailure { logger.warn("Ignoring malformed realtime broker message", it) }
            .getOrNull()
            ?: return
        try {
            localPublisher.publishToUser(routedEvent.userId, routedEvent.event.toApplicationEvent())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error("Realtime broker message delivery failed", error)
        }
    }

    private companion object {
        const val DEFAULT_INBOUND_BUFFER_CAPACITY = 1_024
        const val DROP_LOG_INTERVAL = 100L
        const val OVERFLOW_RECOVERY_COOLDOWN_MILLIS = 5_000L
    }
}

@Serializable
internal data class RoutedRealtimeEvent(
    val userId: String,
    val event: RealtimeEventPayload,
)

internal val realtimeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
