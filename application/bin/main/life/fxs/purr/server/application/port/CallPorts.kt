package life.fxs.purr.server.application.port

import life.fxs.purr.server.application.model.RecordingDownloadResult
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

data class CallRecord(
    val callId: String,
    val pairId: String,
    val roomName: String,
    val createdByUserId: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    /** Provider clock used only to order callbacks from the same recording provider. */
    val recordingProviderUpdatedAtEpochMillis: Long? = null,
    val state: CallState,
    val recordingStatus: RecordingStatus,
    val recordingId: String? = null,
    val recordingRecoveryAttempts: Int = 0,
    val recordingLastRecoveryAtEpochMillis: Long? = null,
    val recordingErrorMessage: String? = null,
    val endedAtEpochMillis: Long? = null,
    val connectedAtEpochMillis: Long? = null,
    val roomEmptySinceEpochMillis: Long? = null,
)

data class ActiveCallResolution(
    val call: CallRecord,
    val created: Boolean,
)

data class EndCallResolution(
    val call: CallRecord,
    val endedNow: Boolean,
)

interface CallSessionStore {
    fun find(callId: String): CallRecord?

    /** Lookup methods used by provider-event application services. */
    fun findByRoomName(roomName: String): CallRecord?

    fun findByRecordingId(recordingId: String): CallRecord?

    fun findActiveByPair(pairId: String): CallRecord?

    fun findOrCreateActive(pairId: String, newCall: () -> CallRecord): ActiveCallResolution

    fun activateIfWaiting(callId: String, connectedAtEpochMillis: Long): CallRecord?

    fun endIfWaiting(callId: String, endedAtEpochMillis: Long): EndCallResolution?

    fun endIfOpen(callId: String, endedAtEpochMillis: Long): EndCallResolution?

    fun claimRecordingStart(callId: String, updatedAtEpochMillis: Long): CallRecord?

    /** Atomically marks an active/start-pending recording as stopping. */
    fun claimRecordingStop(
        callId: String,
        recordingId: String?,
        updatedAtEpochMillis: Long,
    ): CallRecord? = null

    fun findEndedByPairId(pairId: String, limit: Int, cursor: CallHistoryCursor?): List<CallRecord>
}

data class RecordingRecord(
    val recordingId: String,
    val callId: String,
    val status: RecordingStatus,
    val objectKey: String?,
    val location: String?,
    val startedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val durationMillis: Long?,
    val sizeBytes: Long?,
    val errorCode: Int?,
    val errorMessage: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
    val deletionAttempts: Int,
    val lastDeletionAttemptAtEpochMillis: Long?,
    val deletionErrorMessage: String?,
)

data class ProviderRecordingResult(
    val status: RecordingStatus,
    val recordingId: String?,
    val updatedAtEpochMillis: Long,
    val objectKey: String? = null,
    val location: String? = null,
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val durationMillis: Long? = null,
    val sizeBytes: Long? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

interface CallRecordingStore {
    fun updateCurrent(callId: String, result: ProviderRecordingResult): Boolean

    fun findByCallId(callId: String): List<RecordingRecord>

    fun findByRecordingId(recordingId: String): RecordingRecord?

}

interface RecordingConsentStore {
    fun record(callId: String, userId: String, policyVersion: String, consentedAtEpochMillis: Long)

    fun hasAllConsents(callId: String, userIds: Set<String>, policyVersion: String): Boolean
}

fun interface MediaTokenIssuer {
    fun issueAccessToken(roomName: String, participantIdentity: String): String
}

interface RecordingController {
    fun startRecording(callId: String, roomName: String): ProviderRecordingResult

    /**
     * Idempotent operation-aware form used by the durable command dispatcher.
     * Existing adapters may keep implementing the legacy method; providers
     * that support idempotency should use [operationId] as their request key.
     */
    fun startRecording(
        callId: String,
        roomName: String,
        operationId: String,
    ): ProviderRecordingResult = startRecording(callId, roomName)

    fun stopRecording(
        callId: String,
        roomName: String,
        currentRecordingId: String?,
    ): ProviderRecordingResult

    fun stopRecording(
        callId: String,
        roomName: String,
        currentRecordingId: String?,
        operationId: String,
    ): ProviderRecordingResult = stopRecording(callId, roomName, currentRecordingId)

    fun getRecording(recordingId: String): ProviderRecordingResult? = null

    /**
     * Reconciles a durable START command after an uncertain provider response.
     * Implementations should query the provider by the stable operation key and
     * return the existing recording, without creating a new one. Returning null
     * means that no matching provider object is currently visible.
     */
    fun findRecordingForOperation(
        callId: String,
        roomName: String,
        operationId: String,
    ): ProviderRecordingResult? = null
}

fun interface RecordingDownloadProvider {
    fun create(recordingId: String, objectKey: String): RecordingDownloadResult
}

data class RealtimeEvent(
    val type: String,
    val partnerOnline: Boolean? = null,
    val callId: String? = null,
    val pairId: String? = null,
    val callerUserId: String? = null,
    val startedAtEpochMillis: Long? = null,
) {
    companion object {
        const val SNAPSHOT = "snapshot"
        const val PRESENCE_CHANGED = "presence_changed"
        const val CALL_STARTED = "call_started"
        const val CALL_ENDED = "call_ended"
    }
}

fun interface RealtimeEventSink {
    fun publishToUser(userId: String, event: RealtimeEvent)

    fun isReady(): Boolean = true
}

fun interface RealtimeOutbox {
    fun enqueue(
        recipientUserId: String,
        event: RealtimeEvent,
        occurredAtEpochMillis: Long,
    )
}

interface ApplicationTransaction {
    fun <T> execute(block: () -> T): T
}
