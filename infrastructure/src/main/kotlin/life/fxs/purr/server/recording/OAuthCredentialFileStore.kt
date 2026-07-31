package life.fxs.purr.server.recording

import com.google.auth.oauth2.UserCredentials
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal class OAuthCredentialFileStore(
    private val destination: Path,
) : OAuthCredentialStore {
    override fun save(credentials: UserCredentials) {
        val absoluteDestination = destination.toAbsolutePath()
        val parent = requireNotNull(absoluteDestination.parent) {
            "OAuth credential output must have a parent directory"
        }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".purr-google-drive-oauth-", ".json")
        try {
            credentials.save(temporary.toString())
            restrictToOwner(temporary)
            replaceAtomically(temporary, absoluteDestination)
            restrictToOwner(absoluteDestination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun replaceAtomically(source: Path, destination: Path) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restrictToOwner(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // POSIX permissions are unavailable on filesystems such as Windows NTFS.
        }
    }
}
