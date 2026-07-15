package life.fxs.purr.server.application.call

import java.time.Instant
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.model.RecordingResultView
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingCommandProcessor
import life.fxs.purr.server.application.port.RecordingCommandStore
import life.fxs.purr.server.application.port.RecordingConsentStore
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.model.CallDurationPolicy
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

/**
 * Application use cases for explicit recording commands. Production wiring
 * persists a command in the same transaction as the call state transition and
 * lets the recording dispatcher perform provider I/O. The nullable controller
 * is a compatibility adapter for older embedders and unit tests.
 */
class RecordingCommandService(
    private val callAccessPolicy: CallAccessPolicy,
    private val pairService: PairService,
    private val callSessionStore: CallSessionStore,
    private val callRecordingStore: CallRecordingStore,
    private val recordingConsentStore: RecordingConsentStore,
    private val recordingController: RecordingController?,
    private val recordingEnabled: Boolean,
    private val consentPolicyVersion: String,
    private val nowProvider: () -> Instant = Instant::now,
    private val recordingCommandStore: RecordingCommandStore? = null,
    private val transaction: ApplicationTransaction = ImmediateRecordingCommandTransaction,
    private val recordingCommandProcessor: RecordingCommandProcessor? = null,
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
        val requestedAt = nowProvider().toEpochMilli()
        if (!CallDurationPolicy.isRecordingEligible(call.connectedAtEpochMillis, requestedAt)) {
            throw ApplicationException(
                ApplicationError.CONFLICT,
                "Recording is available after 30 seconds of connected call time",
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

        if (recordingCommandStore != null) {
            transaction.execute {
                recordingCommandStore.enqueueStart(
                    callId = call.callId,
                    roomName = call.roomName,
                    requestedAtEpochMillis = requestedAt,
                    availableAtEpochMillis = requestedAt,
                )
            }
            recordingCommandProcessor?.processPending()
            return currentCall(callId).toResultView()
        }

        val claimed = callSessionStore.claimRecordingStartAfterMinimumDuration(
            callId = callId,
            updatedAtEpochMillis = requestedAt,
            minimumConnectedDurationMillis = CallDurationPolicy.MINIMUM_RECORDING_DURATION_MILLIS,
        ) ?: throw ApplicationException(
            ApplicationError.CONFLICT,
            "Recording is already in progress for call: $callId",
        )

        val controller = recordingController ?: throw ApplicationException(
            ApplicationError.EXTERNAL_DEPENDENCY,
            "Recording command dispatcher is not configured",
        )
        val updated = try {
            updateRecording(callId, controller.startRecording(callId, claimed.roomName))
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
            RecordingStatus.RECORDING -> {
                if (recordingCommandStore != null) {
                    val requestedAt = nowProvider().toEpochMilli()
                    val stopping = transaction.execute {
                        val claimed = callSessionStore.claimRecordingStop(
                            callId = callId,
                            recordingId = call.recordingId,
                            updatedAtEpochMillis = requestedAt,
                        ) ?: return@execute null
                        recordingCommandStore.enqueueStop(
                            callId = claimed.callId,
                            roomName = claimed.roomName,
                            recordingId = claimed.recordingId,
                            requestedAtEpochMillis = requestedAt,
                        )
                        claimed
                    } ?: throw ApplicationException(
                        ApplicationError.CONFLICT,
                        "Recording is already stopping for call: $callId",
                    )
                    recordingCommandProcessor?.processPending()
                    currentCall(callId).toResultView()
                } else {
                    val controller = recordingController ?: throw ApplicationException(
                        ApplicationError.EXTERNAL_DEPENDENCY,
                        "Recording command dispatcher is not configured",
                    )
                    updateRecording(
                        callId,
                        controller.stopRecording(callId, call.roomName, call.recordingId),
                    ).toResultView()
                }
            }
            RecordingStatus.STOPPING -> call.toResultView()
            RecordingStatus.IDLE,
            RecordingStatus.STOPPED,
            RecordingStatus.FAILED,
            RecordingStatus.DELETED,
            -> throw ApplicationException(ApplicationError.CONFLICT, "Recording is not active for call: $callId")
        }
    }

    fun stopForCallEnding(call: CallRecord) {
        if (call.recordingStatus !in setOf(RecordingStatus.STARTING, RecordingStatus.RECORDING)) return
        if (recordingCommandStore != null) {
            val requestedAt = nowProvider().toEpochMilli()
            transaction.execute {
                val stopping = callSessionStore.claimRecordingStop(
                    callId = call.callId,
                    recordingId = call.recordingId,
                    updatedAtEpochMillis = requestedAt,
                )
                if (stopping != null) {
                    recordingCommandStore.enqueueStop(
                        callId = stopping.callId,
                        roomName = stopping.roomName,
                        recordingId = stopping.recordingId,
                        requestedAtEpochMillis = requestedAt,
                    )
                }
            }
            recordingCommandProcessor?.processPending()
            return
        }
        val recordingId = call.recordingId ?: return
        val controller = recordingController ?: return
        updateRecording(
            call.callId,
            controller.stopRecording(call.callId, call.roomName, recordingId),
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

    private fun currentCall(callId: String): CallRecord = callSessionStore.find(callId)
        ?: throw ApplicationException(ApplicationError.NOT_FOUND, "Call not found: $callId")

    private fun updateRecording(callId: String, result: ProviderRecordingResult): CallRecord {
        if (!callRecordingStore.updateCurrent(callId, result)) {
            throw ApplicationException(ApplicationError.NOT_FOUND, "Call not found: $callId")
        }
        return currentCall(callId)
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

private object ImmediateRecordingCommandTransaction : ApplicationTransaction {
    override fun <T> execute(block: () -> T): T = block()
}
