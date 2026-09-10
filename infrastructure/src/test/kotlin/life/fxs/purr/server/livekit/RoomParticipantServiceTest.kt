package life.fxs.purr.server.livekit

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.config.LiveKitConfig

class RoomParticipantServiceTest {
    @Test
    fun `provider confirmed missing room is an empty participant inventory`() = withProvider(
        404, """{"code":"not_found","msg":"room does not exist"}""",
    ) { service ->
        assertEquals(0, service.countPresentNonEgressParticipants("room-1"))
        assertEquals(0, service.countActiveNonEgressParticipants("room-1"))
        assertEquals(emptySet(), service.presentNonEgressParticipantIdentities("room-1"))
        assertEquals(emptySet(), service.activeNonEgressParticipantIdentities("room-1"))
    }

    @Test
    fun `proxy not found and provider failures are not empty rooms`() {
        listOf(404 to "missing route", 503 to "unavailable", 401 to "unauthorized").forEach { (status, body) ->
            withProvider(status, body) { service ->
                assertFailsWith<ApplicationException> { service.countPresentNonEgressParticipants("room-1") }
            }
        }
    }

    private fun withProvider(status: Int, body: String, block: (LiveKitRoomParticipantService) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/twirp/livekit.RoomService/ListParticipants") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(LiveKitRoomParticipantService(LiveKitConfig(
                wsUrl = "ws://127.0.0.1",
                httpUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = "devkey",
                apiSecret = "devsecret",
                tokenTtlSeconds = 60,
            )))
        } finally {
            server.stop(0)
        }
    }
}
