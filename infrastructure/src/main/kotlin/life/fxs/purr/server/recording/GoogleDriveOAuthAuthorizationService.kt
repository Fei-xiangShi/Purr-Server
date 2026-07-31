package life.fxs.purr.server.recording

import com.google.auth.oauth2.ClientId
import com.google.auth.oauth2.UserAuthorizer
import com.google.auth.oauth2.UserCredentials
import java.awt.Desktop
import java.net.URI
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

internal class GoogleDriveOAuthAuthorizationService(
    private val gateway: OAuthAuthorizationGateway,
    private val receiverFactory: OAuthCallbackReceiverFactory,
    private val credentialStore: OAuthCredentialStore,
    private val authorizationUrlPresenter: AuthorizationUrlPresenter,
    private val stateGenerator: OAuthStateGenerator = SecureOAuthStateGenerator,
    private val timeout: Duration = Duration.ofMinutes(10),
) {
    fun authorize(): UserCredentials {
        val state = stateGenerator.generate()
        return receiverFactory.create(state).use { receiver ->
            val authorizationUrl = gateway.authorizationUrl(state, receiver.callbackUri)
            authorizationUrlPresenter.present(authorizationUrl)
            val credentials = gateway.exchange(receiver.awaitCode(timeout), receiver.callbackUri)
            require(!credentials.refreshToken.isNullOrBlank()) {
                "Google did not return a refresh token; revoke the app grant and authorize again"
            }
            credentialStore.save(credentials)
            credentials
        }
    }
}

internal interface OAuthAuthorizationGateway {
    fun authorizationUrl(state: String, callbackUri: URI): URI

    fun exchange(code: String, callbackUri: URI): UserCredentials
}

internal class GoogleUserAuthorizationGateway(
    clientId: ClientId,
) : OAuthAuthorizationGateway {
    private val authorizer = UserAuthorizer.newBuilder()
        .setClientId(clientId)
        .setScopes(listOf(GOOGLE_DRIVE_SCOPE))
        .build()

    override fun authorizationUrl(state: String, callbackUri: URI): URI = authorizer.getAuthorizationUrl(
        AUTHORIZATION_USER_ID,
        state,
        callbackUri,
        mapOf(
            "access_type" to "offline",
            "include_granted_scopes" to "true",
            "prompt" to "consent",
        ),
    ).toURI()

    override fun exchange(code: String, callbackUri: URI): UserCredentials =
        authorizer.getCredentialsFromCode(code, callbackUri)
}

internal fun interface OAuthCallbackReceiverFactory {
    fun create(expectedState: String): OAuthCallbackReceiver
}

internal interface OAuthCallbackReceiver : AutoCloseable {
    val callbackUri: URI

    fun awaitCode(timeout: Duration): String
}

internal fun interface OAuthCredentialStore {
    fun save(credentials: UserCredentials)
}

internal fun interface AuthorizationUrlPresenter {
    fun present(uri: URI)
}

internal fun interface OAuthStateGenerator {
    fun generate(): String
}

private object SecureOAuthStateGenerator : OAuthStateGenerator {
    override fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal object ConsoleAuthorizationUrlPresenter : AuthorizationUrlPresenter {
    override fun present(uri: URI) {
        println("Open this Google authorization URL in your browser:")
        println(uri)
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
            }
        }
    }
}

private const val AUTHORIZATION_USER_ID = "purr-recording-archive"
