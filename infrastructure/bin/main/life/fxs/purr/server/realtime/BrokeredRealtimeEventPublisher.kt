@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package life.fxs.purr.server.realtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink
import org.slf4j.LoggerFactory

class BrokeredRealtimeEventPublisher(
    private val broker: RealtimeMessageBroker,
    private val localPublisher: RealtimeEventSink,
    private val json: Json = realtimeJson,
) : RealtimeEventSink, AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        broker.subscribe(::handleMessage)
    }

    override fun publishToUser(userId: String, event: RealtimeEvent) {
        broker.publish(json.encodeToString(RoutedRealtimeEvent(userId, event.toPayload())))
    }

    override fun isReady(): Boolean = broker.isReady()

    override fun close() {
        broker.close()
    }

    private fun handleMessage(message: String) {
        val routedEvent = runCatching { json.decodeFromString<RoutedRealtimeEvent>(message) }
            .onFailure { logger.warn("Ignoring malformed realtime broker message", it) }
            .getOrNull()
            ?: return
        localPublisher.publishToUser(routedEvent.userId, routedEvent.event.toApplicationEvent())
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
