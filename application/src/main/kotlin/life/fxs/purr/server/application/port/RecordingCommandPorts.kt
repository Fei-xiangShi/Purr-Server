package life.fxs.purr.server.application.port

/**
 * Durable commands for recording provider side effects.
 *
 * A command is the source of truth for work that must happen outside the
 * database transaction.  The command row is written together with the call
 * state transition and can therefore be replayed after a process crash.
 */
enum class RecordingCommandType {
    START,
    STOP,
}

enum class RecordingCommandState {
    PENDING,
    SUCCEEDED,
    FAILED,
}

data class RecordingCommandRecord(
    val commandId: String,
    val idempotencyKey: String,
    val callId: String,
    val roomName: String,
    val type: RecordingCommandType,
    val recordingId: String?,
    val requestedAtEpochMillis: Long,
    val availableAtEpochMillis: Long,
    val attemptCount: Int,
    val leaseOwner: String?,
    val leaseUntilEpochMillis: Long?,
    val state: RecordingCommandState,
    val completedAtEpochMillis: Long?,
    val lastError: String?,
)

/**
 * Persistence boundary for recording commands. Implementations must make
 * enqueue and claim operations conditional/atomic so duplicate webhooks and
 * multiple workers cannot execute the same logical command concurrently.
 */
interface RecordingCommandStore {
    fun enqueueStart(
        callId: String,
        roomName: String,
        requestedAtEpochMillis: Long,
        availableAtEpochMillis: Long = requestedAtEpochMillis,
    ): RecordingCommandRecord

    fun enqueueStop(
        callId: String,
        roomName: String,
        recordingId: String?,
        requestedAtEpochMillis: Long,
    ): RecordingCommandRecord

    fun claimBatch(
        workerId: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
        maxAttempts: Int,
        limit: Int,
    ): List<RecordingCommandRecord>

    fun markSucceeded(
        commandId: String,
        workerId: String,
        result: ProviderRecordingResult,
        completedAtEpochMillis: Long,
    ): Boolean

    fun markFailed(
        commandId: String,
        workerId: String,
        availableAtEpochMillis: Long,
        errorMessage: String,
        terminal: Boolean,
        completedAtEpochMillis: Long,
    ): Boolean

    /** Returns commands that have not yet reached a terminal state for a call. */
    fun findOpenForCall(callId: String, type: RecordingCommandType): RecordingCommandRecord?

    /**
     * Reconciles calls left in STARTING/STOPPING after a crash. Implementations
     * should enqueue only missing commands and return the number of new rows.
     */
    fun reconcileOpenCalls(nowEpochMillis: Long): Int = 0
}

/** Optional synchronous wake-up used by request handlers. The durable row is
 * still authoritative; waking the worker only reduces response latency. */
fun interface RecordingCommandWakeup {
    fun wake()
}

/** Optional request-path drain used when an API needs the historical
 * synchronous response. It never replaces the durable worker. */
fun interface RecordingCommandProcessor {
    fun processPending()
}
