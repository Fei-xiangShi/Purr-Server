package life.fxs.purr.server.application.port

/**
 * Provider-neutral room lifecycle events. Infrastructure adapters translate
 * provider webhooks into this model before entering the application layer.
 */
enum class CallRoomEventType {
    PARTICIPANT_JOINED,
    PARTICIPANT_LEFT,
    ROOM_FINISHED,
}

data class CallRoomParticipant(
    val isActive: Boolean,
    val isEgress: Boolean,
    /** Provider identity, when the provider includes one in its event. */
    val identity: String? = null,
)

data class CallRoomEvent(
    val eventId: String,
    val type: CallRoomEventType,
    val roomName: String,
    val participant: CallRoomParticipant? = null,
    val reportedParticipantCount: Int? = null,
)

fun interface CallRoomEventHandler {
    fun handle(event: CallRoomEvent)
}

fun interface WaitingCallTerminator {
    fun endWaitingCall(callId: String, endedAtEpochMillis: Long)
}

/** Persisted coordination boundary used by the periodic room reconciler. */
interface CallRoomReconciliationStore {
    fun findOpenCalls(limit: Int): List<CallRecord>

    fun observeRoomEmpty(callId: String, observedAtEpochMillis: Long): CallRecord?

    fun clearRoomEmptyObservation(callId: String): Boolean
}

/**
 * Reads the authoritative provider-side participant snapshot when available.
 * A null implementation is valid for providers that include a reliable count
 * in their event payload (and for local development adapters).
 */
interface CallRoomParticipantReader {
    fun countActiveNonEgressParticipants(roomName: String): Int

    fun countPresentNonEgressParticipants(roomName: String): Int

    /**
     * Returns the authoritative active identities when the provider can
     * supply them. A null result means this adapter only supports counts and
     * keeps source compatibility with count-only test/local adapters.
     */
    fun activeNonEgressParticipantIdentities(roomName: String): Set<String>? = null

    /**
     * Returns the authoritative present identities when available. As with
     * active identities, null means that only count-based reconciliation is
     * supported by the adapter.
     */
    fun presentNonEgressParticipantIdentities(roomName: String): Set<String>? = null
}
