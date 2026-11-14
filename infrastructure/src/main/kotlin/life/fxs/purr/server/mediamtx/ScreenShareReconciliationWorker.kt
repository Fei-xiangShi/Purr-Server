package life.fxs.purr.server.mediamtx

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
import life.fxs.purr.server.application.call.ScreenShareReconciliationService
import life.fxs.purr.server.config.MediaMtxConfig
import org.slf4j.LoggerFactory

class ScreenShareReconciliationWorker(
    private val config: MediaMtxConfig,
    private val service: ScreenShareReconciliationService,
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
            // Passes run about once a second; an unreachable MediaMTX must not
            // write a stack trace per second for the whole outage.
            var consecutiveFailures = 0
            while (isActive && !closed.get()) {
                try {
                    service.reconcileOnce(nowProvider().toEpochMilli())
                    if (consecutiveFailures > 0) {
                        logger.info(
                            "MediaMTX screen-share reconciliation recovered after {} failed passes",
                            consecutiveFailures,
                        )
                        consecutiveFailures = 0
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    consecutiveFailures++
                    if (consecutiveFailures == 1) {
                        logger.warn("MediaMTX screen-share reconciliation pass failed", error)
                    } else if (consecutiveFailures % REPEATED_FAILURE_LOG_INTERVAL == 0) {
                        logger.warn(
                            "MediaMTX screen-share reconciliation still failing after {} passes: {}",
                            consecutiveFailures,
                            error.toString(),
                        )
                    }
                }
                delay(config.reconciliationIntervalMillis)
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
        const val REPEATED_FAILURE_LOG_INTERVAL = 60
    }
}
