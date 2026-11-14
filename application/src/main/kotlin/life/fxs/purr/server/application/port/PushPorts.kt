package life.fxs.purr.server.application.port

data class PushDeviceRecord(
    val installationId: String,
    val userId: String,
    val sessionId: String,
    val provider: PushProvider,
    val token: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val disabledAtEpochMillis: Long? = null,
)

enum class PushProvider {
    FCM,
}

interface PushDeviceStore {
    fun upsert(device: PushDeviceRecord)

    fun remove(userId: String, installationId: String): Boolean

    fun findActiveByUserId(userId: String): List<PushDeviceRecord>

    fun disable(provider: PushProvider, token: String, disabledAtEpochMillis: Long): Boolean
}

data class IncomingCallPushMessage(
    val callId: String,
    val startedAtEpochMillis: Long,
)

sealed interface PushDeliveryResult {
    data object Delivered : PushDeliveryResult

    data object DeviceUnregistered : PushDeliveryResult

    /** The provider refused this request permanently; retrying the same message cannot succeed. */
    data class Rejected(val reason: String) : PushDeliveryResult
}

fun interface PushNotificationSender {
    suspend fun send(device: PushDeviceRecord, message: IncomingCallPushMessage): PushDeliveryResult

    fun isReady(): Boolean = true
}
