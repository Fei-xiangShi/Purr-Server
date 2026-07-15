package life.fxs.purr.server.push

import java.time.Instant
import kotlinx.coroutines.CancellationException
import life.fxs.purr.server.application.port.IncomingCallPushMessage
import life.fxs.purr.server.application.port.PushDeliveryResult
import life.fxs.purr.server.application.port.PushDeviceStore
import life.fxs.purr.server.application.port.PushNotificationSender
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink

class IncomingCallPushEventSink(
    private val deviceStore: PushDeviceStore,
    private val sender: PushNotificationSender,
    private val enabled: Boolean,
    private val nowProvider: () -> Instant = Instant::now,
) : RealtimeEventSink {
    override suspend fun publishToUser(userId: String, event: RealtimeEvent) {
        if (!enabled || event.type != RealtimeEvent.CALL_STARTED) return
        val callId = event.callId ?: error("Incoming call push event is missing callId")
        val startedAt = event.startedAtEpochMillis
            ?: error("Incoming call push event is missing startedAtEpochMillis")
        val message = IncomingCallPushMessage(callId, startedAt)
        val failures = mutableListOf<Throwable>()

        deviceStore.findActiveByUserId(userId).forEach { device ->
            try {
                when (sender.send(device, message)) {
                    PushDeliveryResult.Delivered -> Unit
                    PushDeliveryResult.DeviceUnregistered -> deviceStore.disable(
                        provider = device.provider,
                        token = device.token,
                        disabledAtEpochMillis = nowProvider().toEpochMilli(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failures += error
            }
        }

        if (failures.isNotEmpty()) {
            val combined = IllegalStateException("Incoming call push delivery failed")
            failures.forEach(combined::addSuppressed)
            throw combined
        }
    }

    override fun isReady(): Boolean = !enabled || sender.isReady()
}
