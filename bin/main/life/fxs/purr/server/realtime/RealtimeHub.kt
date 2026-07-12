package life.fxs.purr.server.realtime

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink
import life.fxs.purr.server.realtime.RealtimeEventEncoder

class RealtimeHub(
    private val eventEncoder: RealtimeEventEncoder = RealtimeEventEncoder(),
) : RealtimeEventSink {
    private val connections = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>()

    fun register(userId: String, connectionId: String, session: WebSocketSession) {
        connections.computeIfAbsent(userId) { ConcurrentHashMap() }[connectionId] = session
    }

    fun unregister(userId: String, connectionId: String) {
        val userConnections = connections[userId] ?: return
        userConnections.remove(connectionId)
        if (userConnections.isEmpty()) {
            connections.remove(userId, userConnections)
        }
    }

    override fun publishToUser(userId: String, event: RealtimeEvent) {
        val frame = Frame.Text(eventEncoder.encode(event))
        connections[userId]?.values?.forEach { session ->
            session.outgoing.trySend(frame)
        }
    }
}
