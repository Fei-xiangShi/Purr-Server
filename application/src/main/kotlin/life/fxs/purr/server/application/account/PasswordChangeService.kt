package life.fxs.purr.server.application.account

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.AuthSessionStore
import life.fxs.purr.server.application.port.PasswordHasher
import life.fxs.purr.server.application.port.PasswordVerifier
import life.fxs.purr.server.application.port.UserAccountReader
import life.fxs.purr.server.application.port.UserCredentialStore

class PasswordChangeService(
    private val userAccountReader: UserAccountReader,
    private val userCredentialStore: UserCredentialStore,
    private val authSessionStore: AuthSessionStore,
    private val passwordVerifier: PasswordVerifier,
    private val passwordHasher: PasswordHasher,
    private val transaction: ApplicationTransaction,
    private val passwordPolicy: PasswordPolicy = PasswordPolicy(),
) {
    fun changePassword(userId: String, currentPassword: String, newPassword: String) {
        if (currentPassword.isEmpty()) {
            throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Current password is required")
        }
        passwordPolicy.validate(newPassword)

        val user = userAccountReader.findById(userId)
            ?: throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Unknown user")
        if (!passwordVerifier.matches(currentPassword, user.passwordHash)) {
            throw ApplicationException(ApplicationError.FORBIDDEN, "Current password is incorrect")
        }
        if (passwordVerifier.matches(newPassword, user.passwordHash)) {
            throw ApplicationException(
                ApplicationError.INVALID_ARGUMENT,
                "New password must be different from the current password",
            )
        }

        val newPasswordHash = passwordHasher.hash(newPassword)
        transaction.execute {
            val replaced = userCredentialStore.replacePasswordHash(
                userId = userId,
                expectedPasswordHash = user.passwordHash,
                newPasswordHash = newPasswordHash,
            )
            if (!replaced) {
                throw ApplicationException(
                    ApplicationError.CONFLICT,
                    "Account credentials changed; retry with the current password",
                )
            }
            authSessionStore.deleteAllByUserId(userId)
        }
    }
}
