package life.fxs.purr.server.livekit

import io.ktor.http.HttpStatusCode
import java.time.Instant
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.config.LiveKitConfig
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.StoredCallSession
import livekit.LivekitEgress
import livekit.LivekitModels
import livekit.LivekitWebhook

class LiveKitWebhookService(
    liveKitConfig: LiveKitConfig,
    private val callSessionRepository: CallSessionRepository,
    private val recordingControlService: RecordingControlService,
    private val roomParticipantService: RoomParticipantService? = null,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val receiver = io.livekit.server.WebhookReceiver(liveKitConfig.apiKey, liveKitConfig.apiSecret)

    fun handle(body: String, authorization: String?): LiveKitWebhookAck {
        val event = try {
            receiver.receive(body, authorization)
        } catch (error: IllegalArgumentException) {
            throw ApiException(HttpStatusCode.Unauthorized, error.message ?: "Invalid LiveKit webhook")
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

    private fun maybeStartRecordingWhenReady(event: LivekitWebhook.WebhookEvent, call: StoredCallSession) {
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
        val claimed = callSessionRepository.claimRecordingStart(
            callId = call.callId,
            updatedAtEpochMillis = nowProvider().toEpochMilli(),
        ) ?: return
        try {
            val result = recordingControlService.startRecording(claimed.callId, claimed.roomName)
            val updated = callSessionRepository.updateRecording(
                callId = claimed.callId,
                recordingStatus = result.status,
                recordingId = result.recordingId,
                updatedAtEpochMillis = result.updatedAtEpochMillis,
            )
            maybeStopEndedCallRecording(updated)
        } catch (_: Throwable) {
            callSessionRepository.updateRecording(
                callId = claimed.callId,
                recordingStatus = RecordingStatus.FAILED,
                recordingId = claimed.recordingId,
                updatedAtEpochMillis = nowProvider().toEpochMilli(),
            )
        }
    }

    private fun maybeStopRecordingWhenRoomEmpty(event: LivekitWebhook.WebhookEvent, call: StoredCallSession) {
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
        val call = callSessionRepository.findByRecordingId(recordingId) ?: return
        val updated = callSessionRepository.updateRecording(
            callId = call.callId,
            recordingStatus = egressInfo.status.toRecordingStatus(),
            recordingId = recordingId,
            updatedAtEpochMillis = nowProvider().toEpochMilli(),
        )
        maybeStopEndedCallRecording(updated)
    }

    private fun maybeStopEndedCallRecording(call: StoredCallSession?) {
        val storedCall = call ?: return
        if (storedCall.state != CallState.ENDED) {
            return
        }
        maybeStopRecording(storedCall)
    }

    private fun maybeStopRecording(call: StoredCallSession) {
        if (call.recordingStatus != RecordingStatus.STARTING && call.recordingStatus != RecordingStatus.RECORDING) {
            return
        }
        val recordingId = call.recordingId ?: return
        try {
            val result = recordingControlService.stopRecording(call.callId, call.roomName, recordingId)
            callSessionRepository.updateRecording(
                callId = call.callId,
                recordingStatus = result.status,
                recordingId = result.recordingId,
                updatedAtEpochMillis = result.updatedAtEpochMillis,
            )
        } catch (_: Throwable) {
            callSessionRepository.updateRecording(
                callId = call.callId,
                recordingStatus = RecordingStatus.FAILED,
                recordingId = recordingId,
                updatedAtEpochMillis = nowProvider().toEpochMilli(),
            )
        }
    }

    private fun LivekitEgress.EgressStatus.toRecordingStatus(): RecordingStatus = when (this) {
        LivekitEgress.EgressStatus.EGRESS_STARTING -> RecordingStatus.STARTING
        LivekitEgress.EgressStatus.EGRESS_ACTIVE -> RecordingStatus.RECORDING
        LivekitEgress.EgressStatus.EGRESS_ENDING -> RecordingStatus.STOPPING
        LivekitEgress.EgressStatus.EGRESS_COMPLETE -> RecordingStatus.STOPPED
        LivekitEgress.EgressStatus.EGRESS_FAILED,
        LivekitEgress.EgressStatus.EGRESS_ABORTED,
        LivekitEgress.EgressStatus.EGRESS_LIMIT_REACHED,
        LivekitEgress.EgressStatus.UNRECOGNIZED,
        -> RecordingStatus.FAILED
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
