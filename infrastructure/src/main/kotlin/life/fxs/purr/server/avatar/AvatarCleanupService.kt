package life.fxs.purr.server.avatar

import java.time.Instant
import java.util.UUID
import kotlin.math.min
import life.fxs.purr.server.application.port.AvatarCleanupTask
import life.fxs.purr.server.application.port.AvatarCleanupTaskStore
import life.fxs.purr.server.application.port.AvatarCleanupTelemetry
import life.fxs.purr.server.application.port.AvatarObjectDeleter
import life.fxs.purr.server.application.port.NoOpAvatarTelemetry
import life.fxs.purr.server.config.AvatarConfig
import org.slf4j.LoggerFactory

class AvatarCleanupService(
    private val config: AvatarConfig,
    private val taskStore: AvatarCleanupTaskStore,
    private val objectDeleter: AvatarObjectDeleter,
    private val orphanReconciler: AvatarOrphanReconciler,
    private val telemetry: AvatarCleanupTelemetry = NoOpAvatarTelemetry,
    private val nowProvider: () -> Instant = Instant::now,
    private val workerId: String = UUID.randomUUID().toString(),
) : AvatarCleanupPass {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun cleanupOnce(): AvatarCleanupSummary {
        val passStartedAt = nowProvider()
        taskStore.purgeCompleted(passStartedAt.minusSeconds(COMPLETED_TASK_RETENTION_SECONDS).toEpochMilli())
        var claimed = 0
        var succeeded = 0
        var failed = 0
        while (claimed < config.cleanupBatchSize) {
            val claimedAt = nowProvider()
            val task = taskStore.claimNext(
                workerId = workerId,
                nowEpochMillis = claimedAt.toEpochMilli(),
                leaseUntilEpochMillis = claimedAt.plusSeconds(CLEANUP_LEASE_SECONDS).toEpochMilli(),
            ) ?: break
            claimed++
            if (deleteTask(task)) succeeded++ else failed++
        }

        try {
            orphanReconciler.reconcile(nowProvider())
        } catch (error: Exception) {
            logger.warn("Avatar orphan reconciliation failed; the scan will restart", error)
        }
        recordBacklog()
        return AvatarCleanupSummary(claimed, succeeded, failed)
    }

    private fun deleteTask(task: AvatarCleanupTask): Boolean {
        try {
            objectDeleter.delete(task.objectKey)
        } catch (error: Exception) {
            val failedAt = nowProvider()
            val recorded = try {
                taskStore.recordFailure(
                    objectKey = task.objectKey,
                    workerId = workerId,
                    availableAtEpochMillis = failedAt.plusSeconds(retryDelaySeconds(task.attemptCount)).toEpochMilli(),
                    message = error.message ?: "Avatar object deletion failed",
                )
            } catch (persistenceError: Exception) {
                logger.warn("Avatar deletion failure could not be persisted for {}", task.objectKey, persistenceError)
                false
            }
            if (!recorded) {
                logger.warn("Avatar cleanup lease was lost after deletion failed for {}", task.objectKey)
            }
            recordCleanup(succeeded = false)
            logger.warn("Avatar deletion failed for {}; retry scheduled", task.objectKey, error)
            return false
        }

        val completed = try {
            taskStore.markCompleted(task.objectKey, workerId, nowProvider().toEpochMilli())
        } catch (error: Exception) {
            logger.warn("Avatar {} was deleted but cleanup completion could not be persisted", task.objectKey, error)
            false
        }
        if (!completed) {
            logger.warn("Avatar cleanup lease was lost after deleting {}", task.objectKey)
        }
        recordCleanup(completed)
        return completed
    }

    private fun recordCleanup(succeeded: Boolean) {
        try {
            telemetry.recordCleanup(succeeded)
        } catch (_: Exception) {
            // Telemetry must never change cleanup semantics.
        }
    }

    private fun recordBacklog() {
        try {
            val backlog = taskStore.backlog(nowProvider().toEpochMilli())
            telemetry.recordBacklog(backlog.pendingTasks, backlog.oldestTaskAgeSeconds)
        } catch (error: Exception) {
            logger.warn("Avatar cleanup backlog measurement failed", error)
        }
    }

    private fun retryDelaySeconds(attempt: Int): Long {
        if (attempt >= config.cleanupMaxAttempts) return config.cleanupRetryMaxSeconds
        var delay = config.cleanupRetryBaseSeconds
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = min(delay * 2, config.cleanupRetryMaxSeconds)
        }
        return delay
    }

    private companion object {
        const val CLEANUP_LEASE_SECONDS = 60L
        const val COMPLETED_TASK_RETENTION_SECONDS = 30L * 24 * 60 * 60
    }
}

data class AvatarCleanupSummary(
    val claimed: Int,
    val succeeded: Int,
    val failed: Int,
)

fun interface AvatarCleanupPass {
    fun cleanupOnce(): AvatarCleanupSummary
}
