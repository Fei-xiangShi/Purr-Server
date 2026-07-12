package life.fxs.purr.server.application.call

import java.time.Instant
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.RecordingResultView
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingConsentStore
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.application.account.PairService

class RecordingCommandService(
    private val callAccessPolicy: CallAccessPolicy,
    private val pairService: PairService,
    private val callSessionStore: CallSessionStore,
    private val callRecordingStore: CallRecordingStore,
    private val recordingConsentStore: RecordingConsentStore,
    private val recordingController: RecordingController,
    private val recordingEnabled: Boolean,
    private val consentPolicyVersion: String,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun startRecording(userId: String, callId: String): RecordingResultView {
        requireRecordingEnabled()
        val call = callAccessPolicy.requireAccessibleCall(userId, callId)
        requireAllRecordingConsents(call)
        if (call.state != CallState.ACTIVE) {
            throw ApplicationException(
                ApplicationError.INVALID_ARGUMENT,
                "Cannot start recording for ended call: $callId",
            )
        }
        when (call.recordingStatus) {
            RecordingStatus.STARTING,
            RecordingStatus.RECORDING,
            RecordingStatus.STOPPING,
            -> throw ApplicationException(
                ApplicationError.CONFLICT,
                "Recording is already in progress for call: $callId",
            )
            RecordingStatus.IDLE,
            RecordingStatus.STOPPED,
            RecordingStatus.FAILED,
            RecordingStatus.DELETED,
            -> Unit
        }
        val claimed = callSessionStore.claimRecordingStart(callId, nowProvider().toEpochMilli())
            ?: throw ApplicationException(
                ApplicationError.CONFLICT,
                "Recording is already in progress for call: $callId",
            )
        val updated = try {
            updateRecording(callId, recordingController.startRecording(callId, claimed.roomName))
        } catch (error: Throwable) {
            updateRecording(
                callId,
                ProviderRecordingResult(
                    status = RecordingStatus.FAILED,
                    recordingId = claimed.recordingId,
                    updatedAtEpochMillis = nowProvider().toEpochMilli(),
                    errorMessage = error.message,
                ),
            )
            throw error
        }
        return updated.toResultView()
    }

    fun stopRecording(userId: String, callId: String): RecordingResultView {
        requireRecordingEnabled()
        val call = callAccessPolicy.requireAccessibleCall(userId, callId)
        return when (call.recordingStatus) {
            RecordingStatus.STARTING -> throw ApplicationException(
                ApplicationError.CONFLICT,
                "Recording is still starting for call: $callId",
            )
            RecordingStatus.RECORDING -> updateRecording(
                callId,
                recordingController.stopRecording(callId, call.roomName, call.recordingId),
            ).toResultView()
            RecordingStatus.STOPPING -> call.toResultView()
            RecordingStatus.IDLE,
            RecordingStatus.STOPPED,
            RecordingStatus.FAILED,
            RecordingStatus.DELETED,
            -> throw ApplicationException(ApplicationError.CONFLICT, "Recording is not active for call: $callId")
        }
    }

    fun stopForCallEnding(call: CallRecord) {
        if (
            call.recordingStatus !in setOf(RecordingStatus.STARTING, RecordingStatus.RECORDING) ||
            call.recordingId.isNullOrBlank()
        ) {
            return
        }
        updateRecording(
            call.callId,
            recordingController.stopRecording(call.callId, call.roomName, call.recordingId),
        )
    }

    private fun requireAllRecordingConsents(call: CallRecord) {
        val hasAllConsents = recordingConsentStore.hasAllConsents(
            callId = call.callId,
            userIds = pairService.requirePairUserIds(call.pairId),
            policyVersion = consentPolicyVersion,
        )
        if (!hasAllConsents) {
            throw ApplicationException(
                ApplicationError.CONFLICT,
                "Both participants must consent before recording starts",
            )
        }
    }

    private fun updateRecording(callId: String, result: ProviderRecordingResult): CallRecord {
        if (!callRecordingStore.updateCurrent(callId, result)) {
            throw ApplicationException(ApplicationError.NOT_FOUND, "Call not found: $callId")
        }
        return callSessionStore.find(callId)
            ?: throw ApplicationException(ApplicationError.NOT_FOUND, "Call not found: $callId")
    }

    private fun CallRecord.toResultView() = RecordingResultView(
        callId = callId,
        status = recordingStatus,
        recordingId = recordingId,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun requireRecordingEnabled() {
        if (!recordingEnabled) {
            throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Recording is disabled")
        }
    }
}
