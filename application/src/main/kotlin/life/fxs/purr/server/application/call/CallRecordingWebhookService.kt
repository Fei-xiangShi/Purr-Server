package life.fxs.purr.server.application.call

import java.time.Instant
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.application.port.RecordingCommandStore
import life.fxs.purr.server.application.port.RecordingCommandWakeup
import life.fxs.purr.server.application.port.RecordingArchiveWakeup
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

/**
 * Applies provider recording callbacks to the current call and recording
 * history. Provider payload parsing remains in infrastructure; this class
 * only handles application state and the ended-call stop invariant.
 */
class CallRecordingWebhookService(
    private val callSessionStore: CallSessionStore,
    private val callRecordingStore: CallRecordingStore,
    /** Legacy immediate adapter retained for old embedding callers. */
    private val recordingController: RecordingController? = null,
    private val nowProvider: () -> Instant = Instant::now,
    private val recordingCommandStore: RecordingCommandStore? = null,
    private val transaction: ApplicationTransaction = ImmediateRecordingWebhookTransaction,
    private val recordingCommandWakeup: RecordingCommandWakeup? = null,
    private val recordingArchiveWakeup: RecordingArchiveWakeup? = null,
) {
    fun handle(recordingId: String, result: ProviderRecordingResult) {
        if (recordingId.isBlank()) return
        val call = callSessionStore.findByRecordingId(recordingId)
            ?: callRecordingStore.findByRecordingId(recordingId)
                ?.let { callSessionStore.find(it.callId) }
            ?: return
        val updated = updateRecording(call.callId, result.copy(recordingId = recordingId))
        if (result.status == RecordingStatus.STOPPED && updated != null) {
            recordingArchiveWakeup?.wake()
        }
        maybeStopEndedCallRecording(updated)
    }

    private fun maybeStopEndedCallRecording(call: CallRecord?) {
        val storedCall = call ?: return
        if (storedCall.state != CallState.ENDED) return
        if (storedCall.recordingStatus !in setOf(RecordingStatus.STARTING, RecordingStatus.RECORDING)) return
        if (recordingCommandStore != null) {
            val requestedAt = nowProvider().toEpochMilli()
            transaction.execute {
                val stopping = callSessionStore.claimRecordingStop(
                    callId = storedCall.callId,
                    recordingId = storedCall.recordingId,
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
            recordingCommandWakeup?.wake()
            return
        }

        val currentRecordingId = storedCall.recordingId ?: return
        val controller = recordingController ?: return
        try {
            val result = controller.stopRecording(
                storedCall.callId,
                storedCall.roomName,
                currentRecordingId,
            )
            updateRecording(storedCall.callId, result)
        } catch (error: Throwable) {
            updateRecording(
                storedCall.callId,
                ProviderRecordingResult(
                    status = RecordingStatus.FAILED,
                    recordingId = currentRecordingId,
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
}

private object ImmediateRecordingWebhookTransaction : ApplicationTransaction {
    override fun <T> execute(block: () -> T): T = block()
}
