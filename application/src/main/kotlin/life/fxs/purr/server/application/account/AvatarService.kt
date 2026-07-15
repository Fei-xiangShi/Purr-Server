package life.fxs.purr.server.application.account

import java.time.Instant
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.UserProfile
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.AvatarCleanupQueue
import life.fxs.purr.server.application.port.AvatarImageProcessor
import life.fxs.purr.server.application.port.AvatarImageRejectedException
import life.fxs.purr.server.application.port.AvatarImageProcessingUnavailableException
import life.fxs.purr.server.application.port.AvatarObjectDeleter
import life.fxs.purr.server.application.port.AvatarObjectUploader
import life.fxs.purr.server.application.port.AvatarUploadTelemetry
import life.fxs.purr.server.application.port.AvatarUploadOutcome
import life.fxs.purr.server.application.port.AvatarUploadLimits
import life.fxs.purr.server.application.port.NoOpAvatarTelemetry
import life.fxs.purr.server.application.port.UserAccountReader
import life.fxs.purr.server.application.port.UserProfileStore

class AvatarService(
    private val userAccountReader: UserAccountReader,
    private val userProfileStore: UserProfileStore,
    private val imageProcessor: AvatarImageProcessor,
    private val avatarObjectUploader: AvatarObjectUploader,
    private val avatarObjectDeleter: AvatarObjectDeleter,
    private val cleanupQueue: AvatarCleanupQueue,
    private val transaction: ApplicationTransaction,
    private val telemetry: AvatarUploadTelemetry = NoOpAvatarTelemetry,
    private val nowProvider: () -> Instant = Instant::now,
    private val nanoTimeProvider: () -> Long = System::nanoTime,
) {
    fun updateAvatar(userId: String, contentType: String, bytes: ByteArray): UserProfile {
        val startedAt = nanoTimeProvider()
        var outputBytes = 0
        var outcome = AvatarUploadOutcome.FAILED
        try {
            validateSize(bytes)
            val processed = try {
                imageProcessor.process(contentType, bytes)
            } catch (error: AvatarImageRejectedException) {
                throw ApplicationException(
                    ApplicationError.INVALID_ARGUMENT,
                    error.message ?: "Avatar image is invalid",
                )
            } catch (error: AvatarImageProcessingUnavailableException) {
                throw ApplicationException(
                    ApplicationError.TEMPORARILY_UNAVAILABLE,
                    error.message ?: "Avatar processing is temporarily unavailable",
                )
            }
            outputBytes = processed.bytes.size
            val user = userAccountReader.findById(userId)
                ?: throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Unknown user")
            val stored = avatarObjectUploader.put(userId, processed.contentType, processed.bytes)
            var updated = false
            try {
                transaction.execute {
                    updated = userProfileStore.compareAndSetAvatar(
                        userId = userId,
                        expectedVersion = user.avatarVersion,
                        objectKey = stored.objectKey,
                    )
                    val cleanupKey = if (updated) user.avatarObjectKey else stored.objectKey
                    cleanupKey?.let { cleanupQueue.enqueue(it, nowProvider().toEpochMilli()) }
                }
            } catch (error: Exception) {
                when (isCurrentAvatar(userId, stored.objectKey)) {
                    true -> updated = true
                    false -> {
                        updated = false
                        cleanupUnreferencedObject(stored.objectKey)
                    }
                    null -> {
                        updated = false // Reconciliation safely resolves an indeterminate commit later.
                    }
                }
                if (!updated) throw error
            }
            if (!updated) {
                outcome = AvatarUploadOutcome.CONFLICT
                throw ApplicationException(ApplicationError.CONFLICT, "User profile changed; retry the upload")
            }
            outcome = AvatarUploadOutcome.SUCCEEDED
            return currentProfile(userId)
        } catch (error: ApplicationException) {
            if (outcome != AvatarUploadOutcome.CONFLICT) {
                outcome = if (error.error == ApplicationError.INVALID_ARGUMENT) {
                    AvatarUploadOutcome.REJECTED
                } else {
                    AvatarUploadOutcome.FAILED
                }
            }
            throw error
        } finally {
            try {
                telemetry.recordUpload(
                    outcome = outcome,
                    inputBytes = bytes.size,
                    outputBytes = outputBytes,
                    durationNanos = nanoTimeProvider() - startedAt,
                )
            } catch (_: Exception) {
                // Telemetry must never change upload semantics.
            }
        }
    }

    private fun validateSize(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > AvatarUploadLimits.MAX_INPUT_BYTES) {
            throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Avatar must be between 1 byte and 10 MB")
        }
    }

    private fun isCurrentAvatar(userId: String, objectKey: String): Boolean? = try {
        userAccountReader.findById(userId)?.avatarObjectKey == objectKey
    } catch (_: Exception) {
        null
    }

    private fun currentProfile(userId: String): UserProfile {
        val current = checkNotNull(userAccountReader.findById(userId)) {
            "Avatar owner disappeared after a successful update"
        }
        val avatarUrl = checkNotNull(current.avatarUrl) {
            "Avatar URL could not be resolved after a successful update"
        }
        return UserProfile(current.userId, current.displayName, avatarUrl)
    }

    private fun cleanupUnreferencedObject(objectKey: String) {
        try {
            avatarObjectDeleter.delete(objectKey)
        } catch (_: Exception) {
            try {
                cleanupQueue.enqueue(objectKey, nowProvider().toEpochMilli())
            } catch (_: Exception) {
                // The periodic object/reference reconciliation is the final recovery path.
            }
        }
    }

}
