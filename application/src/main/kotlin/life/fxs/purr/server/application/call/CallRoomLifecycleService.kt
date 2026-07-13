package life.fxs.purr.server.application.call

import java.time.Instant
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.CallRoomEvent
import life.fxs.purr.server.application.port.CallRoomEventType
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.CallTerminator
import life.fxs.purr.server.application.port.PairStore
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingConsentStore
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.application.port.RecordingCommandProcessor
import life.fxs.purr.server.application.port.RecordingCommandStore
import life.fxs.purr.server.application.port.RecordingCommandWakeup
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.CallRoomEventHandler
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

/**
 * Application use case for provider-neutral room lifecycle events.
 *
 * The provider adapter is deliberately kept out of this class. This service
 * owns the business boundary: a call becomes active when both participants are
 * present, recording start is conditionally claimed, and an empty room ends the call.
 * All state transitions are conditional in the session store, so webhook
 * retries and concurrent deliveries are safe.
 */
class CallRoomLifecycleService(
    private val callSessionStore: CallSessionStore,
    private val callRecordingStore: CallRecordingStore,
    private val recordingConsentStore: RecordingConsentStore,
    private val pairStore: PairStore,
    /** Legacy immediate adapter retained for source compatibility in tests. */
    private val recordingController: RecordingController? = null,
    private val callLifecycleService: CallLifecycleService,
    private val recordingEnabled: Boolean,
    private val consentPolicyVersion: String,
    private val participantReader: CallRoomParticipantReader? = null,
    private val nowProvider: () -> Instant = Instant::now,
    private val recordingCommandStore: RecordingCommandStore? = null,
    private val transaction: ApplicationTransaction = ImmediateCallRoomTransaction,
    private val recordingCommandWakeup: RecordingCommandWakeup? = null,
    private val recordingCommandProcessor: RecordingCommandProcessor? = null,
) : CallRoomEventHandler, CallTerminator {
    override fun handle(event: CallRoomEvent) {
        val call = callSessionStore.findByRoomName(event.roomName) ?: return
        when (event.type) {
            CallRoomEventType.PARTICIPANT_JOINED -> maybeStartCallWhenReady(event, call)
            CallRoomEventType.PARTICIPANT_LEFT -> maybeEndCallWhenRoomEmpty(event, call)
            CallRoomEventType.ROOM_FINISHED -> terminate(call.callId, nowProvider().toEpochMilli())
        }
    }

    private fun maybeStartCallWhenReady(event: CallRoomEvent, call: CallRecord) {
        val participant = event.participant ?: return
        if (participant.isEgress || !participant.isActive) return

        // The room count alone is not an identity proof. Resolve the pair
        // before activation and, when the provider exposes identities, require
        // both identities issued for this exact call. This prevents an
        // unrelated participant in a reused room from starting the call or
        // recording.
        val pair = pairStore.findByPairId(call.pairId) ?: return
        val expectedIdentities = setOf(
            participantIdentity(pair.userAId, call.callId),
            participantIdentity(pair.userBId, call.callId),
        )
        val activeIdentities = participantReader?.activeNonEgressParticipantIdentities(call.roomName)
        if (activeIdentities != null && !activeIdentities.containsAll(expectedIdentities)) return

        val activeParticipantCount = participantReader
            ?.countActiveNonEgressParticipants(call.roomName)
            ?: event.reportedParticipantCount
            ?: 0
        if (activeParticipantCount < MIN_PARTICIPANTS_TO_START) return

        val activeCall = callSessionStore.activateIfWaiting(
            callId = call.callId,
            connectedAtEpochMillis = nowProvider().toEpochMilli(),
        )?.takeIf { it.state == CallState.ACTIVE } ?: return

        // Activation is a call concern even when recording is disabled.
        if (!recordingEnabled) return

        if (!recordingConsentStore.hasAllConsents(
                callId = activeCall.callId,
                userIds = setOf(pair.userAId, pair.userBId),
                policyVersion = consentPolicyVersion,
            )
        ) {
            return
        }

        val claimed = transaction.execute {
            val requestedAt = nowProvider().toEpochMilli()
            val claimed = callSessionStore.claimRecordingStart(
                callId = activeCall.callId,
                updatedAtEpochMillis = requestedAt,
            ) ?: return@execute null
            recordingCommandStore?.enqueueStart(
                callId = claimed.callId,
                roomName = claimed.roomName,
                requestedAtEpochMillis = requestedAt,
            )
            claimed
        } ?: return

        // The durable path intentionally performs no provider I/O in the
        // webhook transaction. A wake-up only asks the dispatcher to process
        // the persisted command sooner; losing the wake-up is harmless.
        if (recordingCommandStore != null) {
            drainRecordingCommandsBestEffort()
            recordingCommandWakeup?.wake()
            return
        }

        val controller = recordingController ?: return
        try {
            val result = controller.startRecording(claimed.callId, claimed.roomName)
            val updated = updateRecording(claimed.callId, result)
            maybeStopEndedCallRecording(updated)
        } catch (error: Throwable) {
            updateRecording(
                claimed.callId,
                ProviderRecordingResult(
                    status = RecordingStatus.FAILED,
                    recordingId = claimed.recordingId,
                    updatedAtEpochMillis = nowProvider().toEpochMilli(),
                    errorMessage = error.message,
                ),
            )
        }
    }

    private fun maybeEndCallWhenRoomEmpty(event: CallRoomEvent, call: CallRecord) {
        if (event.participant?.isEgress == true) return
        val presentParticipantCount = participantReader
            ?.countPresentNonEgressParticipants(call.roomName)
            ?: event.reportedParticipantCount
            ?: 0
        if (presentParticipantCount != 0) return

        // A provider adapter that can return identities gives us a stronger
        // signal than a potentially stale count. Do not end while either
        // expected participant is still present. Count zero remains required
        // to preserve the room-empty boundary and to ignore egress users.
        val pair = pairStore.findByPairId(call.pairId)
        val presentIdentities = participantReader?.presentNonEgressParticipantIdentities(call.roomName)
        if (pair != null && presentIdentities != null) {
            val expectedIdentities = setOf(
                participantIdentity(pair.userAId, call.callId),
                participantIdentity(pair.userBId, call.callId),
            )
            if (presentIdentities.any(expectedIdentities::contains)) return
        }
        terminate(call.callId, nowProvider().toEpochMilli())
    }

    private fun participantIdentity(userId: String, callId: String): String = "$userId-$callId"

    override fun terminate(callId: String, endedAtEpochMillis: Long) {
        val stopCommandClaimed = transaction.execute {
            val current = callSessionStore.find(callId) ?: return@execute false
            var claimedDurableStop = false
            if (recordingCommandStore != null) {
                val stopping = callSessionStore.claimRecordingStop(
                    callId = current.callId,
                    recordingId = current.recordingId,
                    updatedAtEpochMillis = endedAtEpochMillis,
                )
                if (stopping != null) {
                    recordingCommandStore.enqueueStop(
                        callId = stopping.callId,
                        roomName = stopping.roomName,
                        recordingId = stopping.recordingId,
                        requestedAtEpochMillis = endedAtEpochMillis,
                    )
                    claimedDurableStop = true
                }
            } else {
                // Compatibility path for older callers. Production wiring
                // always supplies the durable command store above.
                maybeStopRecording(current)
            }
            callLifecycleService.endOpenCall(
                callId = current.callId,
                endedAtEpochMillis = endedAtEpochMillis,
            )
            claimedDurableStop
        }
        if (stopCommandClaimed) {
            drainRecordingCommandsBestEffort()
            recordingCommandWakeup?.wake()
        }
    }

    /**
     * The command row is already durable. A synchronous pass makes the
     * webhook response observe the provider result in low-latency cases;
     * failures remain retryable in the background worker.
     */
    private fun drainRecordingCommandsBestEffort() {
        runCatching { recordingCommandProcessor?.processPending() }
    }

    private fun maybeStopEndedCallRecording(call: CallRecord?) {
        val storedCall = call ?: return
        if (storedCall.state == CallState.ENDED) {
            maybeStopRecording(storedCall)
        }
    }

    private fun maybeStopRecording(call: CallRecord) {
        if (call.recordingStatus !in setOf(RecordingStatus.STARTING, RecordingStatus.RECORDING)) return
        val recordingId = call.recordingId ?: return
        val controller = recordingController ?: return
        try {
            val result = controller.stopRecording(call.callId, call.roomName, recordingId)
            updateRecording(call.callId, result)
        } catch (error: Throwable) {
            updateRecording(
                call.callId,
                ProviderRecordingResult(
                    status = RecordingStatus.FAILED,
                    recordingId = recordingId,
                    updatedAtEpochMillis = nowProvider().toEpochMilli(),
                    errorMessage = error.message,
                ),
            )
        }
    }

    private fun updateRecording(callId: String, result: ProviderRecordingResult): CallRecord? {
        if (!callRecordingStore.updateCurrent(callId, result)) return null
        return callSessionStore.find(callId)
    }

    private companion object {
        const val MIN_PARTICIPANTS_TO_START = 2
    }
}

private object ImmediateCallRoomTransaction : ApplicationTransaction {
    override fun <T> execute(block: () -> T): T = block()
}
