package life.fxs.purr.server.recording

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.UserCredentials
import java.net.URI
import java.time.Duration
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleDriveOAuthAuthorizationServiceTest {
    @Test
    fun `authorization coordinates state callback exchange and credential storage`() {
        val credentials = credentials(refreshToken = "refresh-token")
        val gateway = FakeGateway(credentials)
        val receiver = FakeReceiver()
        var receiverState: String? = null
        var presentedUrl: URI? = null
        var storedCredentials: UserCredentials? = null
        val service = GoogleDriveOAuthAuthorizationService(
            gateway = gateway,
            receiverFactory = OAuthCallbackReceiverFactory { state ->
                receiverState = state
                receiver
            },
            credentialStore = OAuthCredentialStore { storedCredentials = it },
            authorizationUrlPresenter = AuthorizationUrlPresenter { presentedUrl = it },
            stateGenerator = OAuthStateGenerator { "fixed-state" },
            timeout = Duration.ofSeconds(1),
        )

        val result = service.authorize()

        assertEquals("fixed-state", receiverState)
        assertEquals("fixed-state", gateway.authorizationState)
        assertEquals(receiver.callbackUri, gateway.authorizationCallback)
        assertEquals("authorization-code", gateway.exchangedCode)
        assertEquals(receiver.callbackUri, gateway.exchangeCallback)
        assertEquals(URI("https://accounts.example.test/authorize"), presentedUrl)
        assertEquals(credentials, storedCredentials)
        assertEquals(credentials, result)
        assertTrue(receiver.closed)
    }

    @Test
    fun `credential without refresh token is rejected before storage`() {
        val receiver = FakeReceiver()
        var stored = false
        val service = GoogleDriveOAuthAuthorizationService(
            gateway = FakeGateway(credentials(refreshToken = null)),
            receiverFactory = OAuthCallbackReceiverFactory { receiver },
            credentialStore = OAuthCredentialStore { stored = true },
            authorizationUrlPresenter = AuthorizationUrlPresenter {},
            stateGenerator = OAuthStateGenerator { "fixed-state" },
            timeout = Duration.ofSeconds(1),
        )

        assertFailsWith<IllegalArgumentException> { service.authorize() }

        assertTrue(!stored)
        assertTrue(receiver.closed)
    }

    private fun credentials(refreshToken: String?): UserCredentials = UserCredentials.newBuilder()
        .setClientId("client-id")
        .setClientSecret("client-secret")
        .setRefreshToken(refreshToken)
        .apply {
            if (refreshToken == null) {
                setAccessToken(AccessToken("short-lived-access-token", Date(System.currentTimeMillis() + 60_000)))
            }
        }
        .build()

    private class FakeGateway(
        private val credentials: UserCredentials,
    ) : OAuthAuthorizationGateway {
        var authorizationState: String? = null
        var authorizationCallback: URI? = null
        var exchangedCode: String? = null
        var exchangeCallback: URI? = null

        override fun authorizationUrl(state: String, callbackUri: URI): URI {
            authorizationState = state
            authorizationCallback = callbackUri
            return URI("https://accounts.example.test/authorize")
        }

        override fun exchange(code: String, callbackUri: URI): UserCredentials {
            exchangedCode = code
            exchangeCallback = callbackUri
            return credentials
        }
    }

    private class FakeReceiver : OAuthCallbackReceiver {
        override val callbackUri: URI = URI("http://127.0.0.1:12345/oauth2/callback")
        var closed = false

        override fun awaitCode(timeout: Duration): String = "authorization-code"

        override fun close() {
            closed = true
        }
    }
}
