package life.fxs.purr.server.application.port

data class StoredAvatar(
    val objectKey: String,
)

object AvatarUploadLimits {
    const val MAX_INPUT_BYTES: Int = 10 * 1024 * 1024
}

data class StoredAvatarObject(
    val objectKey: String,
    val lastModifiedEpochMillis: Long,
)

data class StoredAvatarPage(
    val objects: List<StoredAvatarObject>,
    val nextContinuationToken: String?,
)

fun interface AvatarObjectUploader {
    fun put(userId: String, contentType: String, bytes: ByteArray): StoredAvatar
}

fun interface AvatarObjectDeleter {
    fun delete(objectKey: String)
}

fun interface AvatarObjectCatalog {
    fun listObjects(continuationToken: String?, maxKeys: Int): StoredAvatarPage
}

fun interface AvatarStorageReadiness {
    fun isReady(): Boolean
}

data class ProcessedAvatar(
    val contentType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

fun interface AvatarImageProcessor {
    @Throws(AvatarImageRejectedException::class)
    fun process(contentType: String, bytes: ByteArray): ProcessedAvatar
}

class AvatarImageRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AvatarImageProcessingUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AvatarCleanupQueue {
    fun enqueue(objectKey: String, nowEpochMillis: Long)
}

data class AvatarCleanupTask(
    val objectKey: String,
    val attemptCount: Int,
    val availableAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val leaseUntilEpochMillis: Long?,
)

data class AvatarCleanupBacklog(
    val pendingTasks: Long,
    val oldestTaskAgeSeconds: Long,
)

interface AvatarCleanupTaskStore : AvatarCleanupQueue {
    fun claimNext(
        workerId: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): AvatarCleanupTask?

    fun markCompleted(objectKey: String, workerId: String, nowEpochMillis: Long): Boolean

    fun recordFailure(
        objectKey: String,
        workerId: String,
        availableAtEpochMillis: Long,
        message: String,
    ): Boolean

    fun purgeCompleted(completedBeforeEpochMillis: Long): Int

    fun backlog(nowEpochMillis: Long): AvatarCleanupBacklog
}

enum class AvatarUploadOutcome {
    SUCCEEDED,
    REJECTED,
    CONFLICT,
    FAILED,
}

interface AvatarUploadTelemetry {
    fun recordUpload(
        outcome: AvatarUploadOutcome,
        inputBytes: Int,
        outputBytes: Int,
        durationNanos: Long,
    )
}

interface AvatarCleanupTelemetry {
    fun recordCleanup(succeeded: Boolean)

    fun recordBacklog(pendingTasks: Long, oldestTaskAgeSeconds: Long)
}

interface AvatarTelemetry : AvatarUploadTelemetry, AvatarCleanupTelemetry

object NoOpAvatarTelemetry : AvatarTelemetry {
    override fun recordUpload(outcome: AvatarUploadOutcome, inputBytes: Int, outputBytes: Int, durationNanos: Long) = Unit

    override fun recordCleanup(succeeded: Boolean) = Unit

    override fun recordBacklog(pendingTasks: Long, oldestTaskAgeSeconds: Long) = Unit
}
