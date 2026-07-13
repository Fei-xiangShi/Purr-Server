package life.fxs.purr.server.realtime

import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
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

    override suspend fun publishToUser(userId: String, event: RealtimeEvent) {
        val frame = Frame.Text(eventEncoder.encode(event))
        connections[userId]?.values?.forEach { session ->
            session.outgoing.trySend(frame)
        }
    }

    suspend fun closeAll(reason: CloseReason) {
        val sessions = connections.values.toList().flatMap { userConnections ->
            userConnections.values.toList()
        }
        connections.clear()
        sessions.forEach { session ->
            runCatching {
                withTimeoutOrNull(SESSION_CLOSE_TIMEOUT_MILLIS) {
                    session.close(reason)
                }
            }
        }
    }

    private companion object {
        const val SESSION_CLOSE_TIMEOUT_MILLIS = 1_000L
    }
}
