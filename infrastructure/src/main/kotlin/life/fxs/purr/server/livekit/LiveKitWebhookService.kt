package life.fxs.purr.server.livekit

import java.time.Instant
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.config.LiveKitConfig
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallRecordingConsentRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.config.RecordingConfig
import livekit.LivekitEgress
import livekit.LivekitModels
import livekit.LivekitWebhook

class LiveKitWebhookService(
    liveKitConfig: LiveKitConfig,
    private val callSessionRepository: CallSessionRepository,
    private val callRecordingRepository: CallRecordingRepository,
    private val callRecordingConsentRepository: CallRecordingConsentRepository,
    private val pairBondRepository: PairBondRepository,
    private val recordingConfig: RecordingConfig,
    private val recordingController: RecordingController,
    private val roomParticipantService: RoomParticipantService? = null,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val receiver = io.livekit.server.WebhookReceiver(liveKitConfig.apiKey, liveKitConfig.apiSecret)

    fun handle(body: String, authorization: String?): LiveKitWebhookAck {
        val event = try {
            receiver.receive(body, authorization)
        } catch (error: IllegalArgumentException) {
            throw ApplicationException(
                ApplicationError.UNAUTHENTICATED,
                error.message ?: "Invalid LiveKit webhook",
            )
        }

        maybeSyncAutomaticRecording(event)

        if (event.hasEgressInfo()) {
            syncRecording(event.egressInfo)
        }

        return LiveKitWebhookAck(
            event = event.event,
            id = event.id,
        )
    }

    private fun maybeSyncAutomaticRecording(event: LivekitWebhook.WebhookEvent) {
        if (!event.hasRoom()) {
            return
        }
        val roomName = event.room.name.takeIf { it.isNotBlank() } ?: return
        val call = callSessionRepository.findByRoomName(roomName) ?: return
        when (event.event) {
            PARTICIPANT_JOINED_EVENT -> maybeStartRecordingWhenReady(event, call)
            PARTICIPANT_LEFT_EVENT -> maybeStopRecordingWhenRoomEmpty(event, call)
            ROOM_FINISHED_EVENT -> maybeStopRecording(call)
        }
    }

    private fun maybeStartRecordingWhenReady(event: LivekitWebhook.WebhookEvent, call: CallRecord) {
        if (!event.hasParticipant()) {
            return
        }
        val participant = event.participant
        if (participant.kind == LivekitModels.ParticipantInfo.Kind.EGRESS) {
            return
        }
        if (participant.state != LivekitModels.ParticipantInfo.State.ACTIVE) {
            return
        }
        val activeParticipantCount = roomParticipantService?.countActiveNonEgressParticipants(call.roomName)
            ?: event.room.numParticipants
        if (activeParticipantCount < MIN_PARTICIPANTS_TO_RECORD) {
            return
        }
        val pair = pairBondRepository.findByPairId(call.pairId) ?: return
        if (!callRecordingConsentRepository.hasAllConsents(
                callId = call.callId,
                userIds = setOf(pair.userAId, pair.userBId),
                policyVersion = recordingConfig.consentPolicyVersion,
            )
        ) {
            return
        }
        val claimed = callSessionRepository.claimRecordingStart(
            callId = call.callId,
            updatedAtEpochMillis = nowProvider().toEpochMilli(),
        ) ?: return
        try {
            val result = recordingController.startRecording(claimed.callId, claimed.roomName)
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

    private fun maybeStopRecordingWhenRoomEmpty(event: LivekitWebhook.WebhookEvent, call: CallRecord) {
        if (event.hasParticipant() && event.participant.kind == LivekitModels.ParticipantInfo.Kind.EGRESS) {
            return
        }
        val presentParticipantCount = roomParticipantService?.countPresentNonEgressParticipants(call.roomName)
            ?: event.room.numParticipants
        if (presentParticipantCount != 0) {
            return
        }
        maybeStopRecording(call)
    }

    private fun syncRecording(egressInfo: LivekitEgress.EgressInfo) {
        val recordingId = egressInfo.egressId.takeIf { it.isNotBlank() } ?: return
        val call = callSessionRepository.findByRecordingId(recordingId)
            ?: callRecordingRepository.findByRecordingId(recordingId)
                ?.let { callSessionRepository.find(it.callId) }
            ?: return
        val result = egressInfo.toRecordingResult()
        val updated = updateRecording(call.callId, result.copy(recordingId = recordingId))
        maybeStopEndedCallRecording(updated)
    }

    private fun maybeStopEndedCallRecording(call: CallRecord?) {
        val storedCall = call ?: return
        if (storedCall.state != CallState.ENDED) {
            return
        }
        maybeStopRecording(storedCall)
    }

    private fun maybeStopRecording(call: CallRecord) {
        if (call.recordingStatus != RecordingStatus.STARTING && call.recordingStatus != RecordingStatus.RECORDING) {
            return
        }
        val recordingId = call.recordingId ?: return
        try {
            val result = recordingController.stopRecording(call.callId, call.roomName, recordingId)
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
        if (!callRecordingRepository.updateCurrent(callId, result)) return null
        return callSessionRepository.find(callId)
    }

    private companion object {
        const val PARTICIPANT_JOINED_EVENT = "participant_joined"
        const val PARTICIPANT_LEFT_EVENT = "participant_left"
        const val ROOM_FINISHED_EVENT = "room_finished"
        const val MIN_PARTICIPANTS_TO_RECORD = 2
    }
}

@kotlinx.serialization.Serializable
data class LiveKitWebhookAck(
    val event: String,
    val id: String,
)
