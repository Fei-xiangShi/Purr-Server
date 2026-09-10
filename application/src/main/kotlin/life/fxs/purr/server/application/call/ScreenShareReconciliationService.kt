package life.fxs.purr.server.application.call

import java.util.concurrent.ConcurrentHashMap
import life.fxs.purr.server.application.port.ScreenShareProvider
import life.fxs.purr.server.application.port.ScreenShareProviderPath
import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.application.port.ScreenShareStore
import life.fxs.purr.server.model.ScreenShareStatus

class ScreenShareReconciliationService(
    private val store: ScreenShareStore,
    private val provider: ScreenShareProvider,
    private val lifecycleService: ScreenShareLifecycleService,
    private val batchSize: Int,
) {
    private val inboundByteObservations = ConcurrentHashMap<String, Long>()

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
                        providerPath.inboundBytes?.let { inboundByteObservations[current.shareId] = it }
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
                    if (providerPath != null && providerPath.hasInboundProgress(current.shareId)) {
                        store.observePresent(
                            shareId = current.shareId,
                            providerSourceType = providerPath.sourceType,
                            providerSourceId = providerPath.sourceId,
                            observedAtEpochMillis = nowEpochMillis,
                        )
                    } else {
                        val missing = store.observeMissing(current.shareId, nowEpochMillis)
                        val requiredSnapshots = if (providerPath == null) {
                            REQUIRED_MISSING_SNAPSHOTS
                        } else {
                            REQUIRED_STALLED_SNAPSHOTS
                        }
                        if (missing != null && missing.missingSnapshotCount >= requiredSnapshots) {
                            val stopped = lifecycleService.stopped(
                                shareId = current.shareId,
                                stoppedAtEpochMillis = nowEpochMillis,
                                message = if (providerPath == null) {
                                    "Publisher disconnected"
                                } else {
                                    "Publisher stopped sending media"
                                },
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
        inboundByteObservations.remove(record.shareId)
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

    private fun ScreenShareProviderPath.hasInboundProgress(shareId: String): Boolean {
        val currentBytes = inboundBytes ?: return true
        val previousBytes = inboundByteObservations.put(shareId, currentBytes) ?: return true
        return currentBytes != previousBytes
    }

    private companion object {
        const val REQUIRED_MISSING_SNAPSHOTS = 2
        const val REQUIRED_STALLED_SNAPSHOTS = 5
        const val MAX_ERROR_LENGTH = 2_048
        val activeStatuses = setOf(ScreenShareStatus.AUTHORIZED, ScreenShareStatus.LIVE)
    }
}
