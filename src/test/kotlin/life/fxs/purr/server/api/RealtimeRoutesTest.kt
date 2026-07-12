package life.fxs.purr.server.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class RealtimeRoutesTest {
    @Test
    fun `presence and call lifecycle are delivered to the partner`() = testApplication {
        val realtimeClient = createClient {
            install(WebSockets)
        }
        val userAToken = realtimeClient.login("user-a", "pass-a")
        val userBToken = realtimeClient.login("user-b", "pass-b")

        coroutineScope {
            val eventsForB = Channel<String>(Channel.UNLIMITED)
            val bConnected = CompletableDeferred<Unit>()
            val bJob = launch {
                realtimeClient.webSocket(
                    request = {
                        url("/realtime")
                        header(HttpHeaders.Authorization, "Bearer $userBToken")
                    },
                ) {
                    bConnected.complete(Unit)
                    repeat(EXPECTED_B_EVENT_COUNT) {
                        eventsForB.send((incoming.receive() as Frame.Text).readText())
                    }
                }
            }
            bConnected.await()
            val initialSnapshot = withTimeout(EVENT_TIMEOUT_MILLIS) { eventsForB.receive() }
            assertTrue(initialSnapshot.contains("\"type\":\"snapshot\""))
            assertTrue(initialSnapshot.contains("\"partnerOnline\":false"))

            val aConnected = CompletableDeferred<Unit>()
            val aJob = launch {
                realtimeClient.webSocket(
                    request = {
                        url("/realtime")
                        header(HttpHeaders.Authorization, "Bearer $userAToken")
                    },
                ) {
                    aConnected.complete(Unit)
                    awaitCancellation()
                }
            }
            aConnected.await()

            val presenceEvent = withTimeout(EVENT_TIMEOUT_MILLIS) { eventsForB.receive() }
            assertTrue(presenceEvent.contains("\"type\":\"presence_changed\""))
            assertTrue(presenceEvent.contains("\"partnerOnline\":true"))

            val pair = realtimeClient.get("/pair") {
                header(HttpHeaders.Authorization, "Bearer $userBToken")
            }
            assertEquals(HttpStatusCode.OK, pair.status)
            assertTrue(pair.bodyAsText().contains("\"isOnline\":true"))

            val session = realtimeClient.post("/calls/session") {
                header(HttpHeaders.Authorization, "Bearer $userAToken")
                contentType(ContentType.Application.Json)
                setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
            }
            assertEquals(HttpStatusCode.OK, session.status)
            val callId = Regex("\\\"callId\\\":\\\"([^\\\"]+)\\\"")
                .find(session.bodyAsText())
                ?.groupValues
                ?.get(1)
                ?: error("Missing callId")

            val callStarted = withTimeout(EVENT_TIMEOUT_MILLIS) { eventsForB.receive() }
            assertTrue(callStarted.contains("\"type\":\"call_started\""))
            assertTrue(callStarted.contains("\"callId\":\"$callId\""))

            val activeCall = realtimeClient.get("/calls/active") {
                header(HttpHeaders.Authorization, "Bearer $userBToken")
            }
            assertEquals(HttpStatusCode.OK, activeCall.status)
            assertTrue(activeCall.bodyAsText().contains("\"isIncoming\":true"))
            assertTrue(activeCall.bodyAsText().contains("\"callId\":\"$callId\""))

            val ended = realtimeClient.post("/calls/$callId/end") {
                header(HttpHeaders.Authorization, "Bearer $userAToken")
            }
            assertEquals(HttpStatusCode.OK, ended.status)
            val callEnded = withTimeout(EVENT_TIMEOUT_MILLIS) { eventsForB.receive() }
            assertTrue(callEnded.contains("\"type\":\"call_ended\""))
            assertTrue(callEnded.contains("\"callId\":\"$callId\""))

            aJob.cancelAndJoin()
            bJob.join()
        }
    }

    private suspend fun HttpClient.login(username: String, password: String): String {
        val response = post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"")
            .find(response.bodyAsText())
            ?.groupValues
            ?.get(1)
            ?: error("Missing access token")
    }

    private companion object {
        const val EXPECTED_B_EVENT_COUNT = 4
        const val EVENT_TIMEOUT_MILLIS = 5_000L
    }
}
