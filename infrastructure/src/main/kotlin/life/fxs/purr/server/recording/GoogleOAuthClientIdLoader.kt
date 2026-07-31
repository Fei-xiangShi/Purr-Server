package life.fxs.purr.server.recording

import com.google.auth.oauth2.ClientId
import java.nio.file.Files
import java.nio.file.Path

internal object GoogleOAuthClientIdLoader {
    fun load(path: Path): ClientId = try {
        Files.newInputStream(path).use(ClientId::fromStream)
    } catch (error: Exception) {
        throw IllegalArgumentException("Unable to load Google OAuth desktop client JSON", error)
    }
}
