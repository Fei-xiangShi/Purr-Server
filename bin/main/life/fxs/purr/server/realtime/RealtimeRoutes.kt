package life.fxs.purr.server.realtime

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.auth.AuthenticatedUser
import life.fxs.purr.server.coroutines.onBlockingIo
import life.fxs.purr.server.service.ServerDependencies

fun Route.registerRealtimeRoutes(dependencies: ServerDependencies) {
    authenticate("auth-jwt") {
        webSocket("/realtime") {
            val user = call.principal<AuthenticatedUser>()
                ?: throw ApiException(HttpStatusCode.Unauthorized, "Missing authenticated user")
            val connectionId = UUID.randomUUID().toString()
            val partnerId = onBlockingIo { dependencies.pairService.requirePartnerUserId(user.userId) }

            onBlockingIo {
                dependencies.presenceStore.connect(connectionId, user.userId, Instant.now().toEpochMilli())
            }
            dependencies.realtimeHub.register(user.userId, connectionId, this)
            publishPresence(dependencies, partnerId, online = true)
            publishSnapshot(dependencies, user.userId, partnerId)

            val heartbeatJob = launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                    onBlockingIo {
                        dependencies.presenceStore.heartbeat(connectionId, Instant.now().toEpochMilli())
                    }
                }
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text && frame.readText() == HEARTBEAT_MESSAGE) {
                        onBlockingIo {
                            dependencies.presenceStore.heartbeat(connectionId, Instant.now().toEpochMilli())
                        }
                    }
                }
            } finally {
                heartbeatJob.cancel()
                dependencies.realtimeHub.unregister(user.userId, connectionId)
                val stillOnline = onBlockingIo {
                    dependencies.presenceStore.disconnect(connectionId)
                    dependencies.presenceStore.isOnline(user.userId, Instant.now().toEpochMilli())
                }
                publishPresence(dependencies, partnerId, stillOnline)
            }
        }
    }
}

private suspend fun publishSnapshot(dependencies: ServerDependencies, userId: String, partnerId: String) {
    val partnerOnline = onBlockingIo {
        dependencies.presenceStore.isOnline(partnerId, Instant.now().toEpochMilli())
    }
    val activeCall = onBlockingIo { dependencies.callSessionService.getActiveCall(userId) }
    dependencies.realtimeEventPublisher.publishToUser(
        userId,
        RealtimeEvent(
            type = RealtimeEvent.SNAPSHOT,
            partnerOnline = partnerOnline,
            callId = activeCall?.callId,
            pairId = activeCall?.pairId,
            callerUserId = activeCall?.callerUserId,
            startedAtEpochMillis = activeCall?.startedAtEpochMillis,
        ),
    )
}

private fun publishPresence(dependencies: ServerDependencies, userId: String, online: Boolean) {
    dependencies.realtimeEventPublisher.publishToUser(
        userId,
        RealtimeEvent(type = RealtimeEvent.PRESENCE_CHANGED, partnerOnline = online),
    )
}

private const val HEARTBEAT_MESSAGE = "heartbeat"
private const val HEARTBEAT_INTERVAL_MILLIS = 15_000L
