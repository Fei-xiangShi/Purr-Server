package life.fxs.purr.server.push

import kotlinx.coroutines.CancellationException
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink

class CompositeRealtimeEventSink(
    private val sinks: List<RealtimeEventSink>,
) : RealtimeEventSink {
    init {
        require(sinks.isNotEmpty()) { "At least one event sink is required" }
    }

    override suspend fun publishToUser(userId: String, event: RealtimeEvent) {
        val failures = mutableListOf<Throwable>()
        sinks.forEach { sink ->
            try {
                sink.publishToUser(userId, event)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failures += error
            }
        }
        if (failures.isNotEmpty()) {
            val combined = IllegalStateException("One or more user event transports failed")
            failures.forEach(combined::addSuppressed)
            throw combined
        }
    }

    override fun isReady(): Boolean = sinks.all(RealtimeEventSink::isReady)
}
