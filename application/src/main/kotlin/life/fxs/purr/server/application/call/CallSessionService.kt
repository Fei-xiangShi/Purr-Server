package life.fxs.purr.server.application.call

import java.time.Instant
import java.util.UUID
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.ActiveCallResult
import life.fxs.purr.server.application.model.CallSessionResult
import life.fxs.purr.server.application.model.CallStatusResult
import life.fxs.purr.server.application.model.CreateCallSessionCommand
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.MediaTokenIssuer
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.application.port.RecordingConsentStore
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.application.account.PairService

class CallSessionService(
    private val pairService: PairService,
    private val callAccessPolicy: CallAccessPolicy,
    private val callSessionStore: CallSessionStore,
    private val recordingConsentStore: RecordingConsentStore,
    private val mediaTokenIssuer: MediaTokenIssuer,
    private val mediaServerWsUrl: String,
    private val recordingEnabled: Boolean,
    private val consentPolicyVersion: String,
    private val transaction: ApplicationTransaction,
    private val realtimeOutbox: RealtimeOutbox,
    private val nowProvider: () -> Instant = Instant::now,
    private val callIdProvider: () -> String = { "call-${UUID.randomUUID()}" },
) {
    fun createSession(userId: String, command: CreateCallSessionCommand): CallSessionResult {
        val call = transaction.execute {
            pairService.requirePairAccess(userId, command.pairId)
            if (recordingEnabled && !command.recordingConsent) {
                throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Explicit recording consent is required")
            }

            val resolvedCall = callSessionStore.findOrCreateActive(command.pairId) {
                if (command.expectedCallId != null) {
                    throw ApplicationException(
                        ApplicationError.CONFLICT,
                        "Incoming call is no longer active: ${command.expectedCallId}",
                    )
                }
                newCall(command.pairId, userId)
            }.also { resolution ->
                command.expectedCallId?.let { expectedCallId ->
                    if (resolution.call.callId != expectedCallId || resolution.call.createdByUserId == userId) {
                        throw ApplicationException(
                            ApplicationError.CONFLICT,
                            "Incoming call is no longer active: $expectedCallId",
                        )
                    }
                }
                if (resolution.created) {
                    realtimeOutbox.enqueue(
                        recipientUserId = pairService.requirePartnerUserId(userId),
                        event = resolution.call.toRealtimeEvent(),
                        occurredAtEpochMillis = resolution.call.startedAtEpochMillis,
                    )
                }
            }
            if (recordingEnabled) {
                recordingConsentStore.record(
                    callId = resolvedCall.call.callId,
                    userId = userId,
                    policyVersion = consentPolicyVersion,
                    consentedAtEpochMillis = nowProvider().toEpochMilli(),
                )
            }
            resolvedCall
        }
        return call.call.toSessionResult(userId, createdByRequest = call.created)
    }

    fun getCall(userId: String, callId: String): CallStatusResult {
        val serverNow = nowProvider().toEpochMilli()
        return callAccessPolicy.requireAccessibleCall(userId, callId).toStatusResult(serverNow)
    }

    fun getActiveCall(userId: String): ActiveCallResult? {
        val pairId = pairService.requirePairId(userId)
        return callSessionStore.findActiveByPair(pairId)?.toActiveCallResult(userId)
    }

    fun endCall(userId: String, callId: String) {
        callAccessPolicy.requireAccessibleCall(userId, callId)
        // The HTTP end acknowledgement represents only this user's local
        // hang-up. It must not transition the shared call to ENDED or delete
        // the LiveKit room while the peer is still connected. The room
        // lifecycle service observes the participant_left webhooks and ends
        // the shared call only after the room has become empty (or LiveKit
        // reports room_finished). Keeping this endpoint idempotent also lets
        // the ApplicationScope retry it without blocking either participant.
    }

    private fun newCall(pairId: String, createdByUserId: String): CallRecord {
        val callId = callIdProvider()
        val now = nowProvider().toEpochMilli()
        return CallRecord(
            callId = callId,
            pairId = pairId,
            roomName = "$pairId-$callId",
            createdByUserId = createdByUserId,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            state = CallState.WAITING,
            recordingStatus = RecordingStatus.IDLE,
        )
    }

    private fun CallRecord.toSessionResult(userId: String, createdByRequest: Boolean): CallSessionResult {
        val participantIdentity = "$userId-$callId"
        return CallSessionResult(
            callId = callId,
            pairId = pairId,
            roomName = roomName,
            participantIdentity = participantIdentity,
            token = mediaTokenIssuer.issueAccessToken(roomName, participantIdentity),
            wsUrl = mediaServerWsUrl,
            createdByRequest = createdByRequest,
        )
    }

    private fun CallRecord.toStatusResult(serverNowEpochMillis: Long): CallStatusResult {
        val durationMillis = connectedAtEpochMillis?.let { connectedAt ->
            ((endedAtEpochMillis ?: serverNowEpochMillis) - connectedAt).coerceAtLeast(0L)
        }
        return CallStatusResult(
            callId = callId,
            pairId = pairId,
            state = state,
            recordingStatus = recordingStatus,
            startedAtEpochMillis = connectedAtEpochMillis,
            endedAtEpochMillis = endedAtEpochMillis,
            durationMillis = durationMillis,
            serverNowEpochMillis = serverNowEpochMillis,
        )
    }

    private fun CallRecord.toActiveCallResult(userId: String) = ActiveCallResult(
        callId = callId,
        pairId = pairId,
        callerUserId = createdByUserId,
        isIncoming = createdByUserId != userId,
        startedAtEpochMillis = startedAtEpochMillis,
    )

    private fun CallRecord.toRealtimeEvent() = RealtimeEvent(
        type = RealtimeEvent.CALL_STARTED,
        callId = callId,
        pairId = pairId,
        callerUserId = createdByUserId,
        startedAtEpochMillis = startedAtEpochMillis,
    )
}
