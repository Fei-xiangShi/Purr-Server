package life.fxs.purr.server.avatar

import java.time.Instant
import life.fxs.purr.server.application.port.AvatarCleanupQueue
import life.fxs.purr.server.application.port.AvatarObjectCatalog
import life.fxs.purr.server.application.port.AvatarReferenceReader
import life.fxs.purr.server.config.AvatarConfig

class AvatarOrphanReconciler(
    private val config: AvatarConfig,
    private val cleanupQueue: AvatarCleanupQueue,
    private val objectCatalog: AvatarObjectCatalog,
    private val referenceReader: AvatarReferenceReader,
) {
    private var continuationToken: String? = null

    fun reconcile(now: Instant) {
        try {
            val page = objectCatalog.listObjects(
                continuationToken,
                config.cleanupBatchSize.coerceAtMost(MAX_LIST_KEYS),
            )
            val cutoff = now.minusSeconds(config.orphanGraceSeconds).toEpochMilli()
            val oldObjects = page.objects.filter { it.lastModifiedEpochMillis < cutoff }
            val referenced = referenceReader.findReferencedObjectKeys(
                oldObjects.mapTo(mutableSetOf()) { it.objectKey },
            )
            oldObjects.asSequence()
                .map { it.objectKey }
                .filterNot(referenced::contains)
                .forEach { cleanupQueue.enqueue(it, now.toEpochMilli()) }
            continuationToken = page.nextContinuationToken
        } catch (error: Exception) {
            continuationToken = null
            throw error
        }
    }

    private companion object {
        const val MAX_LIST_KEYS = 1_000
    }
}
