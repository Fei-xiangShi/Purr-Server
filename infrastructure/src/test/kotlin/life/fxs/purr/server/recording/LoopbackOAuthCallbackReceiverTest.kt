package life.fxs.purr.server.recording

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoopbackOAuthCallbackReceiverTest {
    @Test
    fun `valid state returns authorization code`() {
        LoopbackOAuthCallbackReceiver("expected-state").use { receiver ->
            val response = sendCallback(receiver.callbackUri, "code=auth-code&state=expected-state")

            assertEquals(200, response.statusCode())
            assertEquals("auth-code", receiver.awaitCode(Duration.ofSeconds(1)))
        }
    }

    @Test
    fun `invalid state is rejected at callback boundary`() {
        LoopbackOAuthCallbackReceiver("expected-state").use { receiver ->
            val response = sendCallback(receiver.callbackUri, "code=auth-code&state=wrong-state")

            assertEquals(400, response.statusCode())
            assertFailsWith<IllegalStateException> { receiver.awaitCode(Duration.ofSeconds(1)) }
        }
    }

    private fun sendCallback(callbackUri: URI, query: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("$callbackUri?$query"))
            .GET()
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }
}
