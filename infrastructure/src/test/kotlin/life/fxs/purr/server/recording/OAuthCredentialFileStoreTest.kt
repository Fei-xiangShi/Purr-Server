package life.fxs.purr.server.recording

import com.google.auth.oauth2.UserCredentials
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import life.fxs.purr.server.config.GoogleDriveConfig

class OAuthCredentialFileStoreTest {
    @Test
    fun `authorized user credential is atomically stored and loadable by archive`() {
        val directory = Files.createTempDirectory("purr-drive-oauth-test")
        val destination = directory.resolve("authorized-user.json")
        try {
            val credentials = UserCredentials.newBuilder()
                .setClientId("client-id")
                .setClientSecret("client-secret")
                .setRefreshToken("refresh-token")
                .build()

            OAuthCredentialFileStore(destination).save(credentials)

            val loaded = loadDriveCredentials(
                GoogleDriveConfig(
                    enabled = true,
                    oauthCredentialPath = destination.toString(),
                    folderId = "drive-folder-123456",
                ),
            )
            assertEquals("client-id", loaded.clientId)
            assertEquals("client-secret", loaded.clientSecret)
            assertEquals("refresh-token", loaded.refreshToken)
            assertTrue(Files.isRegularFile(destination))
            if (Files.getFileStore(destination).supportsFileAttributeView("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(destination),
                )
            }
        } finally {
            Files.deleteIfExists(destination)
            Files.deleteIfExists(directory)
        }
    }
}
