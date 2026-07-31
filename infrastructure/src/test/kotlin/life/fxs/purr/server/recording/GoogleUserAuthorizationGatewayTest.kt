package life.fxs.purr.server.recording

import com.google.auth.oauth2.ClientId
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleUserAuthorizationGatewayTest {
    @Test
    fun `authorization redirect matches loopback receiver callback`() {
        LoopbackOAuthCallbackReceiver("expected-state").use { receiver ->
            val gateway = GoogleUserAuthorizationGateway(
                ClientId.of("client.apps.googleusercontent.com", "client-secret"),
            )

            val authorizationUrl = gateway.authorizationUrl("expected-state", receiver.callbackBaseUri)

            assertEquals(
                receiver.callbackUri.toString(),
                queryParameters(authorizationUrl)["redirect_uri"],
            )
        }
    }

    private fun queryParameters(uri: URI): Map<String, String> = uri.rawQuery
        .split('&')
        .associate { parameter ->
            val (key, value) = parameter.split('=', limit = 2)
            URLDecoder.decode(key, StandardCharsets.UTF_8) to
                URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
}
