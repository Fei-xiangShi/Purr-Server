package life.fxs.purr.server.recording

import java.nio.file.Files
import java.nio.file.Path

object GoogleDriveOAuthAuthorizer {
    @JvmStatic
    fun main(args: Array<String>) {
        val command = GoogleDriveOAuthCommand.parse(args)
        GoogleDriveOAuthAuthorizationService(
            gateway = GoogleUserAuthorizationGateway(
                GoogleOAuthClientIdLoader.load(command.clientSecretsPath),
            ),
            receiverFactory = OAuthCallbackReceiverFactory(::LoopbackOAuthCallbackReceiver),
            credentialStore = OAuthCredentialFileStore(command.credentialOutputPath),
            authorizationUrlPresenter = ConsoleAuthorizationUrlPresenter,
        ).authorize()
        println("Google Drive OAuth credentials written to ${command.credentialOutputPath}")
    }
}

internal data class GoogleDriveOAuthCommand(
    val clientSecretsPath: Path,
    val credentialOutputPath: Path,
) {
    companion object {
        fun parse(args: Array<String>): GoogleDriveOAuthCommand {
            require(args.size == 2) {
                "Usage: authorizeGoogleDrive <desktop-client-json> <authorized-user-output-json>"
            }
            val clientSecretsPath = Path.of(args[0]).toAbsolutePath()
            require(Files.isRegularFile(clientSecretsPath) && Files.isReadable(clientSecretsPath)) {
                "Google OAuth desktop client JSON is not a readable file: $clientSecretsPath"
            }
            return GoogleDriveOAuthCommand(
                clientSecretsPath = clientSecretsPath,
                credentialOutputPath = Path.of(args[1]).toAbsolutePath(),
            )
        }
    }
}
