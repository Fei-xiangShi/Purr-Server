package life.fxs.purr.server.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.config.LiveKitConfig
import life.fxs.purr.server.livekit.LiveKitTokenService
import life.fxs.purr.server.livekit.RecordingControlService
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingResponseDto
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.model.SessionRequestDto
import life.fxs.purr.server.model.SessionResponseDto
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.StoredCallSession

class CallService(
    private val pairService: PairService,
    private val callSessionRepository: CallSessionRepository,
    private val liveKitTokenService: LiveKitTokenService,
    private val recordingControlService: RecordingControlService,
    private val liveKitConfig: LiveKitConfig,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun createSession(userId: String, request: SessionRequestDto): SessionResponseDto {
        pairService.requirePairAccess(userId, request.pairId)

        val call = when (val resumeCallId = request.resumeCallId) {
            null -> callSessionRepository.findActiveByPair(request.pairId) ?: createNewCall(request.pairId, userId)
            else -> requireAccessibleCall(userId, resumeCallId).also {
                if (it.state != CallState.ACTIVE) {
                    throw ApiException(HttpStatusCode.BadRequest, "Call $resumeCallId is not active")
                }
            }
        }

        return call.toSessionResponseDto(userId)
    }

    fun getCall(userId: String, callId: String) =
        requireAccessibleCall(userId, callId).toCallStatusDto()

    fun endCall(userId: String, callId: String) {
        val call = requireAccessibleCall(userId, callId)
        when (call.recordingStatus) {
            RecordingStatus.STARTING,
            RecordingStatus.RECORDING,
            -> if (!call.recordingId.isNullOrBlank()) {
                val result = recordingControlService.stopRecording(callId, call.roomName, call.recordingId)
                callSessionRepository.updateRecording(
                    callId = callId,
                    recordingStatus = result.status,
                    recordingId = result.recordingId,
                    updatedAtEpochMillis = result.updatedAtEpochMillis,
                ) ?: throw ApiException(HttpStatusCode.NotFound, "Call not found: $callId")
            }
            RecordingStatus.IDLE,
            RecordingStatus.STOPPING,
            RecordingStatus.STOPPED,
            RecordingStatus.FAILED,
            -> Unit
        }
        callSessionRepository.end(callId, nowProvider().toEpochMilli())
            ?: throw ApiException(HttpStatusCode.NotFound, "Call not found: $callId")
    }

    fun startRecording(userId: String, callId: String): RecordingResponseDto {
        val call = requireAccessibleCall(userId, callId)
        if (call.state != CallState.ACTIVE) {
            throw ApiException(HttpStatusCode.BadRequest, "Cannot start recording for ended call: $callId")
        }
        when (call.recordingStatus) {
            RecordingStatus.STARTING,
            RecordingStatus.RECORDING,
            RecordingStatus.STOPPING,
            -> throw ApiException(HttpStatusCode.BadRequest, "Recording is already in progress for call: $callId")
            RecordingStatus.IDLE,
            RecordingStatus.STOPPED,
            RecordingStatus.FAILED,
            -> Unit
        }
        val result = recordingControlService.startRecording(callId, call.roomName)
        val updated = callSessionRepository.updateRecording(
            callId = callId,
            recordingStatus = result.status,
            recordingId = result.recordingId,
            updatedAtEpochMillis = result.updatedAtEpochMillis,
        ) ?: throw ApiException(HttpStatusCode.NotFound, "Call not found: $callId")
        return updated.toRecordingResponseDto()
    }

    fun stopRecording(userId: String, callId: String): RecordingResponseDto {
        val call = requireAccessibleCall(userId, callId)
        return when (call.recordingStatus) {
            RecordingStatus.STARTING -> throw ApiException(
                HttpStatusCode.BadRequest,
                "Recording is still starting for call: $callId",
            )
            RecordingStatus.RECORDING -> {
                val result = recordingControlService.stopRecording(callId, call.roomName, call.recordingId)
                val updated = callSessionRepository.updateRecording(
                    callId = callId,
                    recordingStatus = result.status,
                    recordingId = result.recordingId,
                    updatedAtEpochMillis = result.updatedAtEpochMillis,
                ) ?: throw ApiException(HttpStatusCode.NotFound, "Call not found: $callId")
                updated.toRecordingResponseDto()
            }
            RecordingStatus.STOPPING -> call.toRecordingResponseDto()
            RecordingStatus.IDLE,
            RecordingStatus.STOPPED,
            RecordingStatus.FAILED,
            -> throw ApiException(HttpStatusCode.BadRequest, "Recording is not active for call: $callId")
        }
    }

    private fun createNewCall(pairId: String, createdByUserId: String): StoredCallSession {
        val callId = "call-${UUID.randomUUID()}"
        val now = nowProvider().toEpochMilli()
        return callSessionRepository.upsert(
            StoredCallSession(
                callId = callId,
                pairId = pairId,
                roomName = "$pairId-$callId",
                createdByUserId = createdByUserId,
                startedAtEpochMillis = now,
                updatedAtEpochMillis = now,
                state = CallState.ACTIVE,
                recordingStatus = RecordingStatus.IDLE,
            ),
        )
    }

    private fun requireAccessibleCall(userId: String, callId: String): StoredCallSession {
        val call = callSessionRepository.find(callId)
            ?: throw ApiException(HttpStatusCode.NotFound, "Call not found: $callId")
        pairService.requirePairAccess(userId, call.pairId)
        return call
    }

    private fun StoredCallSession.toSessionResponseDto(userId: String): SessionResponseDto {
        val participantIdentity = "$userId-$callId"
        return SessionResponseDto(
            callId = callId,
            pairId = pairId,
            roomName = roomName,
            participantIdentity = participantIdentity,
            token = liveKitTokenService.issueAccessToken(
                roomName = roomName,
                participantIdentity = participantIdentity,
            ),
            wsUrl = liveKitConfig.wsUrl,
        )
    }

    private fun StoredCallSession.toRecordingResponseDto(): RecordingResponseDto = RecordingResponseDto(
        callId = callId,
        status = recordingStatus.wireValue,
        recordingId = recordingId,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
