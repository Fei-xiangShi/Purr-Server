package life.fxs.purr.server.realtime

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.application.port.RealtimeEventSink
import life.fxs.purr.server.config.OutboxConfig
import org.slf4j.LoggerFactory

class OutboxDispatcher(
    private val config: OutboxConfig,
    private val repository: OutboxRepository,
    private val eventSink: RealtimeEventSink,
    private val nowProvider: () -> Instant = Instant::now,
    private val workerId: String = "outbox-${UUID.randomUUID()}",
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        check(!closed.get()) { "Outbox dispatcher is closed" }
        check(started.compareAndSet(false, true)) { "Outbox dispatcher is already started" }
        job = scope.launch {
            while (isActive && !closed.get()) {
                try {
                    dispatchOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("Realtime outbox dispatch pass failed", error)
                }
                delay(config.pollIntervalMillis)
            }
        }
    }

    internal fun dispatchOnce(now: Instant = nowProvider()): OutboxDispatchSummary {
        val nowEpochMillis = now.toEpochMilli()
        val leaseUntilEpochMillis = now.plusSeconds(config.leaseSeconds).toEpochMilli()
        if (!repository.acquireDispatcherLease(workerId, nowEpochMillis, leaseUntilEpochMillis)) {
            return OutboxDispatchSummary(0, 0, 0)
        }
        return try {
            dispatchClaimedBatch(now, nowEpochMillis, leaseUntilEpochMillis)
        } finally {
            repository.releaseDispatcherLease(workerId)
        }
    }

    private fun dispatchClaimedBatch(
        now: Instant,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): OutboxDispatchSummary {
        val records = repository.claimBatch(
            workerId = workerId,
            nowEpochMillis = nowEpochMillis,
            leaseUntilEpochMillis = leaseUntilEpochMillis,
            maxAttempts = config.maxAttempts,
            limit = config.batchSize,
        )
        var published = 0
        var failed = 0
        records.forEach { record ->
            check(
                repository.acquireDispatcherLease(
                    workerId = workerId,
                    nowEpochMillis = nowProvider().toEpochMilli(),
                    leaseUntilEpochMillis = nowProvider().plusSeconds(config.leaseSeconds).toEpochMilli(),
                ),
            ) { "Lost realtime outbox dispatcher lease" }
            runCatching { eventSink.publishToUser(record.recipientUserId, record.event) }
                .onSuccess {
                    check(repository.markPublished(record.eventId, workerId, nowEpochMillis)) {
                        "Lost outbox lease before marking event ${record.eventId} as published"
                    }
                    published++
                }
                .onFailure { error ->
                    val retryDelaySeconds = retryDelaySeconds(record.attemptCount)
                    repository.markFailed(
                        eventId = record.eventId,
                        workerId = workerId,
                        availableAtEpochMillis = now.plusSeconds(retryDelaySeconds).toEpochMilli(),
                        errorMessage = error.message ?: error::class.simpleName ?: "Realtime publication failed",
                    )
                    failed++
                    if (record.attemptCount >= config.maxAttempts) {
                        logger.error("Realtime outbox event {} exhausted retries", record.eventId, error)
                    } else {
                        logger.warn("Realtime outbox event {} publication failed", record.eventId, error)
                    }
                }
        }
        return OutboxDispatchSummary(records.size, published, failed)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job?.cancel()
        runBlocking { job?.join() }
        scope.cancel()
    }

    private fun retryDelaySeconds(attemptCount: Int): Long {
        val multiplier = 1L shl min(attemptCount - 1, MAX_BACKOFF_SHIFT)
        return min(config.retryBaseSeconds * multiplier, config.retryMaxSeconds)
    }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 20
    }
}

data class OutboxDispatchSummary(
    val claimed: Int,
    val published: Int,
    val failed: Int,
)
