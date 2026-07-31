package life.fxs.purr.server.recording

import java.nio.file.Files
import java.nio.file.Path

object GoogleDriveOAuthAuthorizer {
    @JvmStatic
    fun main(args: Array<String>) {
        val command = GoogleDriveOAuthCommand.parse(args)
        val credentials = GoogleDriveOAuthAuthorizationService(
            gateway = GoogleUserAuthorizationGateway(
                GoogleOAuthClientIdLoader.load(command.clientSecretsPath),
            ),
            receiverFactory = OAuthCallbackReceiverFactory(::LoopbackOAuthCallbackReceiver),
            credentialStore = OAuthCredentialFileStore(command.credentialOutputPath),
            authorizationUrlPresenter = ConsoleAuthorizationUrlPresenter,
        ).authorize()
        val folderId = GoogleDriveFolderProvisioner(
            GoogleApiDriveFolderGateway(credentials),
        ).ensureFolder(command.folderName)
        println("Google Drive OAuth credentials written to ${command.credentialOutputPath}")
        println("PURR_GOOGLE_DRIVE_FOLDER_ID=$folderId")
    }
}

internal data class GoogleDriveOAuthCommand(
    val clientSecretsPath: Path,
    val credentialOutputPath: Path,
    val folderName: String,
) {
    companion object {
        fun parse(args: Array<String>): GoogleDriveOAuthCommand {
            require(args.size in 2..3) {
                "Usage: authorizeGoogleDrive <desktop-client-json> <authorized-user-output-json> [folder-name]"
            }
            val clientSecretsPath = Path.of(args[0]).toAbsolutePath()
            require(Files.isRegularFile(clientSecretsPath) && Files.isReadable(clientSecretsPath)) {
                "Google OAuth desktop client JSON is not a readable file: $clientSecretsPath"
            }
            return GoogleDriveOAuthCommand(
                clientSecretsPath = clientSecretsPath,
                credentialOutputPath = Path.of(args[1]).toAbsolutePath(),
                folderName = args.getOrElse(2) { DEFAULT_ARCHIVE_FOLDER_NAME },
            )
        }
    }
}

private const val DEFAULT_ARCHIVE_FOLDER_NAME = "Purr Recordings"
