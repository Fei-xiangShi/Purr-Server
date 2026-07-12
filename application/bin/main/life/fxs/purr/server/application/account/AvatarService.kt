package life.fxs.purr.server.application.account

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.UserProfile
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.AvatarObjectStore
import life.fxs.purr.server.application.port.UserAccountStore

class AvatarService(
    private val userAccountStore: UserAccountStore,
    private val avatarObjectStore: AvatarObjectStore,
    private val transaction: ApplicationTransaction,
) {
    fun updateAvatar(userId: String, contentType: String, bytes: ByteArray): UserProfile {
        validate(contentType, bytes)
        val user = userAccountStore.findById(userId)
            ?: throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Unknown user")
        val stored = avatarObjectStore.put(userId, contentType, bytes)
        try {
            transaction.execute {
                if (!userAccountStore.updateAvatarUrl(userId, stored.url)) {
                    throw ApplicationException(ApplicationError.CONFLICT, "User profile changed; retry the upload")
                }
            }
        } catch (throwable: Throwable) {
            runCatching { avatarObjectStore.deleteByUrl(stored.url) }
            throw throwable
        }
        user.avatarUrl?.let { oldUrl -> runCatching { avatarObjectStore.deleteByUrl(oldUrl) } }
        return UserProfile(user.userId, user.displayName, stored.url)
    }

    private fun validate(contentType: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) {
            throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Avatar must be between 1 byte and 10 MB")
        }
        val normalizedType = contentType.lowercase()
        val validSignature = when (normalizedType) {
            JPEG -> bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            PNG -> bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            WEBP -> bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
            else -> false
        }
        if (!validSignature) {
            throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Avatar must be a valid JPEG, PNG, or WebP image")
        }
    }

    private companion object {
        const val MAX_AVATAR_BYTES = 10 * 1024 * 1024
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
        const val WEBP = "image/webp"
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)
