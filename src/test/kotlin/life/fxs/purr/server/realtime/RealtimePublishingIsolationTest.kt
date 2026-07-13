package life.fxs.purr.server.realtime

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink

class RealtimePublishingIsolationTest {
    @Test
    fun `slow realtime broker does not prevent liveness requests`() = testApplication {
        val broker = SuspendingRealtimeMessageBroker()
        val publisher = BrokeredRealtimeEventPublisher(
            broker = broker,
            localPublisher = RealtimeEventSink { _, _ -> },
        )
        application {
            routing {
                get("/slow-publish") {
                    publisher.publishToUser(
                        userId = "user-b",
                        event = RealtimeEvent(type = RealtimeEvent.PRESENCE_CHANGED, partnerOnline = true),
                    )
                    call.respondText("published")
                }
            }
        }

        try {
            coroutineScope {
                val slowPublication = async { client.get("/slow-publish") }
                broker.publishStarted.await()

                val liveResponse = withTimeout(LIVENESS_TIMEOUT_MILLIS) {
                    client.get("/health/live")
                }
                assertEquals(HttpStatusCode.OK, liveResponse.status)
                assertEquals("{\"status\":\"ok\"}", liveResponse.bodyAsText())

                broker.acknowledgement.complete(Unit)
                assertEquals(HttpStatusCode.OK, slowPublication.await().status)
            }
        } finally {
            broker.acknowledgement.complete(Unit)
            publisher.close()
        }
    }

    private companion object {
        const val LIVENESS_TIMEOUT_MILLIS = 1_000L
    }
}

private class SuspendingRealtimeMessageBroker : RealtimeMessageBroker {
    val publishStarted = CompletableDeferred<Unit>()
    val acknowledgement = CompletableDeferred<Unit>()
    private var open = true

    override fun subscribe(handler: (String) -> Unit) = Unit

    override suspend fun publish(message: String) {
        check(open)
        publishStarted.complete(Unit)
        acknowledgement.await()
    }

    override fun isReady(): Boolean = open

    override fun close() {
        open = false
    }
}
