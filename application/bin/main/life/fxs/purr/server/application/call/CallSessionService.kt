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

            if (command.resumeCallId != null) {
                throw ApplicationException(
                    ApplicationError.INVALID_ARGUMENT,
                    "Call reconnection is not supported; create a new call session",
                )
            }
            val resolvedCall = callSessionStore.findOrCreateActive(command.pairId) {
                newCall(command.pairId, userId)
            }.also { resolution ->
                if (resolution.created) {
                    realtimeOutbox.enqueue(
                        recipientUserId = pairService.requirePartnerUserId(userId),
                        event = resolution.call.toRealtimeEvent(),
                        occurredAtEpochMillis = resolution.call.startedAtEpochMillis,
                    )
                }
            }.call
            if (recordingEnabled) {
                recordingConsentStore.record(
                    callId = resolvedCall.callId,
                    userId = userId,
                    policyVersion = consentPolicyVersion,
                    consentedAtEpochMillis = nowProvider().toEpochMilli(),
                )
            }
            resolvedCall
        }
        return call.toSessionResult(userId)
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
        val call = callAccessPolicy.requireAccessibleCall(userId, callId)
        if (call.state != CallState.WAITING) return
        transaction.execute {
            val endedAt = nowProvider().toEpochMilli()
            val resolution = callSessionStore.endIfWaiting(callId, endedAt)
                ?: throw ApplicationException(ApplicationError.NOT_FOUND, "Call not found: $callId")
            if (resolution.endedNow) {
                realtimeOutbox.enqueue(
                    recipientUserId = pairService.requirePartnerUserId(userId),
                    event = RealtimeEvent(
                        type = RealtimeEvent.CALL_ENDED,
                        callId = callId,
                        pairId = call.pairId,
                    ),
                    occurredAtEpochMillis = endedAt,
                )
            }
        }
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

    private fun CallRecord.toSessionResult(userId: String): CallSessionResult {
        val participantIdentity = "$userId-$callId"
        return CallSessionResult(
            callId = callId,
            pairId = pairId,
            roomName = roomName,
            participantIdentity = participantIdentity,
            token = mediaTokenIssuer.issueAccessToken(roomName, participantIdentity),
            wsUrl = mediaServerWsUrl,
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
