package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.port.ScreenShareProvider
import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.application.port.ScreenShareStore
import life.fxs.purr.server.model.ScreenShareStatus

class ScreenShareReconciliationService(
    private val store: ScreenShareStore,
    private val provider: ScreenShareProvider,
    private val lifecycleService: ScreenShareLifecycleService,
    private val batchSize: Int,
) {
    fun reconcileOnce(nowEpochMillis: Long) {
        val initialCandidates = store.findReconciliationCandidates(nowEpochMillis, batchSize)
        if (initialCandidates.isEmpty()) return
        initialCandidates
            .filter { it.status in activeStatuses && it.expiresAtEpochMillis <= nowEpochMillis }
            .forEach { lifecycleService.expire(it.shareId, nowEpochMillis) }

        val candidates = store.findReconciliationCandidates(nowEpochMillis, batchSize)
        val snapshot = try {
            provider.snapshot()
        } catch (error: Throwable) {
            candidates.forEach {
                store.recordProviderError(it.shareId, nowEpochMillis, error.boundedMessage())
            }
            throw error
        }

        candidates.forEach { candidate ->
            val current = store.findByShareId(candidate.shareId) ?: return@forEach
            val providerPath = snapshot.paths[current.mediaPath]?.takeIf { it.online }
            when (current.status) {
                ScreenShareStatus.AUTHORIZED -> {
                    if (providerPath != null) {
                        val transition = store.markLive(
                            shareId = current.shareId,
                            providerSourceType = providerPath.sourceType,
                            providerSourceId = providerPath.sourceId,
                            observedAtEpochMillis = nowEpochMillis,
                        )
                        if (transition?.changed == true) lifecycleService.publish(transition.record, nowEpochMillis)
                    }
                }
                ScreenShareStatus.LIVE -> {
                    if (providerPath != null) {
                        store.observePresent(
                            shareId = current.shareId,
                            providerSourceType = providerPath.sourceType,
                            providerSourceId = providerPath.sourceId,
                            observedAtEpochMillis = nowEpochMillis,
                        )
                    } else {
                        val missing = store.observeMissing(current.shareId, nowEpochMillis)
                        if (missing != null && missing.missingSnapshotCount >= REQUIRED_MISSING_SNAPSHOTS) {
                            val stopped = lifecycleService.stopped(
                                shareId = current.shareId,
                                stoppedAtEpochMillis = nowEpochMillis,
                                message = "Publisher disconnected",
                            )
                            if (stopped != null) cleanup(stopped, nowEpochMillis)
                        }
                    }
                }
                ScreenShareStatus.STOPPING,
                ScreenShareStatus.STOPPED,
                ScreenShareStatus.EXPIRED,
                ScreenShareStatus.FAILED,
                -> cleanup(current, nowEpochMillis)
            }
        }
    }

    private fun cleanup(record: ScreenShareRecord, nowEpochMillis: Long) {
        try {
            provider.cleanup(record)
            val terminal = if (record.status == ScreenShareStatus.STOPPING) {
                lifecycleService.stopped(record.shareId, nowEpochMillis) ?: record
            } else {
                record
            }
            store.markProviderCleaned(terminal.shareId, nowEpochMillis)
        } catch (error: Throwable) {
            store.recordProviderError(record.shareId, nowEpochMillis, error.boundedMessage())
        }
    }

    private fun Throwable.boundedMessage(): String =
        (message ?: this::class.simpleName ?: "provider error").take(MAX_ERROR_LENGTH)

    private companion object {
        const val REQUIRED_MISSING_SNAPSHOTS = 2
        const val MAX_ERROR_LENGTH = 2_048
        val activeStatuses = setOf(ScreenShareStatus.AUTHORIZED, ScreenShareStatus.LIVE)
    }
}
