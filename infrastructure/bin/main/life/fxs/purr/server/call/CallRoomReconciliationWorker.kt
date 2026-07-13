package life.fxs.purr.server.call

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.application.call.CallRoomReconciliationService
import life.fxs.purr.server.config.CallReconciliationConfig
import org.slf4j.LoggerFactory

/** Schedules durable room convergence independently of HTTP/webhook requests. */
class CallRoomReconciliationWorker(
    private val config: CallReconciliationConfig,
    private val service: CallRoomReconciliationService,
    private val nowProvider: () -> Instant = Instant::now,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (!config.enabled || closed.get() || !started.compareAndSet(false, true)) return
        job = scope.launch {
            while (isActive && !closed.get()) {
                try {
                    service.reconcileOnce(nowProvider().toEpochMilli())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("Call room reconciliation pass failed", error)
                }
                delay(config.intervalSeconds * MILLIS_PER_SECOND)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        runBlocking { job?.cancelAndJoin() }
        scope.cancel()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
