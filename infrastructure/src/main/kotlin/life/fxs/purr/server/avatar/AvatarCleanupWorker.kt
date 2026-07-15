package life.fxs.purr.server.avatar

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.config.AvatarConfig
import org.slf4j.LoggerFactory

class AvatarCleanupWorker(
    private val config: AvatarConfig,
    private val cleanupPass: AvatarCleanupPass,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (closed.get() || !config.cleanupEnabled || !started.compareAndSet(false, true)) return
        job = scope.launch {
            while (isActive && !closed.get()) {
                try {
                    cleanupPass.cleanupOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.error("Avatar cleanup pass failed", error)
                }
                delay(config.cleanupIntervalSeconds * MILLIS_PER_SECOND)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        runBlocking { job?.join() }
        scope.cancel()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
