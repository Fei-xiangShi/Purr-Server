package life.fxs.purr.server.recording

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleOAuthClientIdLoaderTest {
    @Test
    fun `Google desktop client JSON is parsed`() {
        val clientJson = Files.createTempFile("purr-google-client", ".json")
        try {
            Files.writeString(clientJson, DESKTOP_CLIENT_JSON)

            val clientId = GoogleOAuthClientIdLoader.load(clientJson)

            assertEquals("purr-client.apps.googleusercontent.com", clientId.clientId)
            assertEquals("client-secret", clientId.clientSecret)
        } finally {
            Files.deleteIfExists(clientJson)
        }
    }

    @Test
    fun `malformed client JSON fails without exposing its content`() {
        val clientJson = Files.createTempFile("purr-google-client", ".json")
        try {
            Files.writeString(clientJson, "not-json-with-secret-value")

            val error = assertFailsWith<IllegalArgumentException> {
                GoogleOAuthClientIdLoader.load(clientJson)
            }

            assertEquals("Unable to load Google OAuth desktop client JSON", error.message)
        } finally {
            Files.deleteIfExists(clientJson)
        }
    }

    private companion object {
        const val DESKTOP_CLIENT_JSON = """
            {
              "installed": {
                "client_id": "purr-client.apps.googleusercontent.com",
                "project_id": "purr-recording-archive",
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "https://oauth2.googleapis.com/token",
                "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
                "client_secret": "client-secret",
                "redirect_uris": ["http://localhost"]
              }
            }
        """
    }
}
