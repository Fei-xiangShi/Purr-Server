package life.fxs.purr.server.application.account

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.UserProfile
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.UserAccountStore

class ProfileService(
    private val userAccountStore: UserAccountStore,
    private val transaction: ApplicationTransaction,
) {
    fun updateDisplayName(userId: String, requestedDisplayName: String): UserProfile {
        val displayName = requestedDisplayName.trim()
        if (displayName.isEmpty() || displayName.length > MAX_DISPLAY_NAME_LENGTH || displayName.any(Char::isISOControl)) {
            throw ApplicationException(
                ApplicationError.INVALID_ARGUMENT,
                "Display name must contain 1 to $MAX_DISPLAY_NAME_LENGTH visible characters",
            )
        }
        val user = transaction.execute {
            val current = userAccountStore.findById(userId)
                ?: throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Unknown user")
            if (!userAccountStore.updateDisplayName(userId, displayName)) {
                throw ApplicationException(ApplicationError.CONFLICT, "User profile changed; retry the update")
            }
            current
        }
        return UserProfile(user.userId, displayName, user.avatarUrl)
    }

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 100
    }
}
