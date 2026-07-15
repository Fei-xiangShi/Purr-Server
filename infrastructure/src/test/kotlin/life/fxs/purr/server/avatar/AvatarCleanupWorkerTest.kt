package life.fxs.purr.server.avatar

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import life.fxs.purr.server.application.port.AvatarCleanupTelemetry
import life.fxs.purr.server.application.port.AvatarObjectCatalog
import life.fxs.purr.server.application.port.AvatarObjectDeleter
import life.fxs.purr.server.application.port.AvatarReferenceReader
import life.fxs.purr.server.application.port.NoOpAvatarTelemetry
import life.fxs.purr.server.application.port.StoredAvatarPage
import life.fxs.purr.server.application.port.StoredAvatarObject
import life.fxs.purr.server.config.AvatarConfig
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory

class AvatarCleanupServiceTest {
    @Test
    fun `failed deletion is durably retried and completed`() {
        val resources = DatabaseFactory(databaseConfig()).connect()
        try {
            val repository = AvatarCleanupRepository()
            val store = FakeAvatarStore(failuresRemaining = 1)
            var now = Instant.ofEpochMilli(1_000)
            val worker = cleanupService(
                config = avatarConfig(),
                taskStore = repository,
                objectDeleter = store,
                objectCatalog = store,
                referenceReader = { emptySet() },
                nowProvider = { now },
                workerId = "worker-a",
            )
            repository.enqueue(OBJECT_KEY, 1_000)

            assertEquals(AvatarCleanupSummary(1, 0, 1), worker.cleanupOnce())
            val failed = assertNotNull(repository.find(OBJECT_KEY))
            assertEquals(1, failed.attemptCount)
            assertNull(failed.completedAtEpochMillis)
            assertEquals(6_000, failed.availableAtEpochMillis)

            now = Instant.ofEpochMilli(5_999)
            assertEquals(AvatarCleanupSummary(0, 0, 0), worker.cleanupOnce())
            now = Instant.ofEpochMilli(6_000)
            assertEquals(AvatarCleanupSummary(1, 1, 0), worker.cleanupOnce())
            val completed = assertNotNull(repository.find(OBJECT_KEY))
            assertEquals(2, completed.attemptCount)
            assertEquals(6_000, completed.completedAtEpochMillis)
            assertEquals(listOf(OBJECT_KEY, OBJECT_KEY), store.deletedKeys)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `orphan reconciliation queues only old unreferenced objects`() {
        val resources = DatabaseFactory(databaseConfig()).connect()
        try {
            val repository = AvatarCleanupRepository()
            val telemetry = FakeCleanupTelemetry()
            val store = FakeAvatarStore(
                failuresRemaining = 0,
                listedObjects = listOf(
                    StoredAvatarObject(ORPHAN_KEY, 1_000),
                    StoredAvatarObject(REFERENCED_KEY, 1_000),
                    StoredAvatarObject(RECENT_KEY, 9_000),
                ),
            )
            val worker = cleanupService(
                config = avatarConfig().copy(orphanGraceSeconds = 5),
                taskStore = repository,
                objectDeleter = store,
                objectCatalog = store,
                referenceReader = { candidates -> candidates.intersect(setOf(REFERENCED_KEY)) },
                telemetry = telemetry,
                nowProvider = { Instant.ofEpochMilli(10_000) },
                workerId = "worker-a",
            )

            worker.cleanupOnce()

            assertNotNull(repository.find(ORPHAN_KEY))
            assertNull(repository.find(REFERENCED_KEY))
            assertNull(repository.find(RECENT_KEY))
            assertEquals(listOf(1L to 0L), telemetry.backlogs)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `cleanup retries indefinitely at capped backoff after the attempt threshold`() {
        val resources = DatabaseFactory(databaseConfig()).connect()
        try {
            val repository = AvatarCleanupRepository()
            val store = FakeAvatarStore(failuresRemaining = 2)
            var now = Instant.ofEpochMilli(1_000)
            val worker = cleanupService(
                config = avatarConfig().copy(
                    cleanupMaxAttempts = 2,
                    cleanupRetryBaseSeconds = 1,
                    cleanupRetryMaxSeconds = 10,
                ),
                taskStore = repository,
                objectDeleter = store,
                objectCatalog = store,
                referenceReader = { emptySet() },
                nowProvider = { now },
                workerId = "worker-a",
            )
            repository.enqueue(OBJECT_KEY, now.toEpochMilli())

            assertEquals(AvatarCleanupSummary(1, 0, 1), worker.cleanupOnce())
            now = Instant.ofEpochMilli(2_000)
            assertEquals(AvatarCleanupSummary(1, 0, 1), worker.cleanupOnce())
            now = Instant.ofEpochMilli(11_999)
            assertEquals(AvatarCleanupSummary(0, 0, 0), worker.cleanupOnce())
            now = Instant.ofEpochMilli(12_000)
            assertEquals(AvatarCleanupSummary(1, 1, 0), worker.cleanupOnce())

            assertEquals(3, assertNotNull(repository.find(OBJECT_KEY)).attemptCount)
        } finally {
            resources.close()
        }
    }

    @Test
    fun `each sequential deletion receives a fresh lease`() {
        val resources = DatabaseFactory(databaseConfig()).connect()
        try {
            val repository = AvatarCleanupRepository()
            var now = Instant.ofEpochMilli(1_000)
            var deletionCount = 0
            val store = FakeAvatarStore(
                failuresRemaining = 0,
                onDelete = { key ->
                    deletionCount++
                    if (deletionCount == 1) {
                        now = Instant.ofEpochMilli(50_000)
                    } else {
                        assertEquals(110_000, assertNotNull(repository.find(key)).leaseUntilEpochMillis)
                    }
                },
            )
            val worker = cleanupService(
                config = avatarConfig().copy(cleanupBatchSize = 2),
                taskStore = repository,
                objectDeleter = store,
                objectCatalog = store,
                referenceReader = { emptySet() },
                nowProvider = { now },
                workerId = "worker-a",
            )
            repository.enqueue(OBJECT_KEY, 1_000)
            repository.enqueue(ORPHAN_KEY, 1_000)

            assertEquals(AvatarCleanupSummary(2, 2, 0), worker.cleanupOnce())
        } finally {
            resources.close()
        }
    }

    @Test
    fun `orphan scan restarts after a continuation token fails`() {
        val resources = DatabaseFactory(databaseConfig()).connect()
        try {
            val repository = AvatarCleanupRepository()
            val deleter = FakeAvatarStore(failuresRemaining = 0)
            val catalog = FailingContinuationCatalog()
            val worker = cleanupService(
                config = avatarConfig(),
                taskStore = repository,
                objectDeleter = deleter,
                objectCatalog = catalog,
                referenceReader = { emptySet() },
                nowProvider = { Instant.ofEpochMilli(10_000) },
                workerId = "worker-a",
            )

            worker.cleanupOnce()
            worker.cleanupOnce()
            worker.cleanupOnce()

            assertEquals(listOf(null, "next", null), catalog.receivedTokens)
        } finally {
            resources.close()
        }
    }

    private fun cleanupService(
        config: AvatarConfig,
        taskStore: AvatarCleanupRepository,
        objectDeleter: AvatarObjectDeleter,
        objectCatalog: AvatarObjectCatalog,
        referenceReader: AvatarReferenceReader,
        telemetry: AvatarCleanupTelemetry = NoOpAvatarTelemetry,
        nowProvider: () -> Instant,
        workerId: String,
    ) = AvatarCleanupService(
        config = config,
        taskStore = taskStore,
        objectDeleter = objectDeleter,
        orphanReconciler = AvatarOrphanReconciler(
            config = config,
            cleanupQueue = taskStore,
            objectCatalog = objectCatalog,
            referenceReader = referenceReader,
        ),
        telemetry = telemetry,
        nowProvider = nowProvider,
        workerId = workerId,
    )

    private fun databaseConfig() = DatabaseConfig(
        jdbcUrl = "jdbc:h2:mem:avatar-cleanup-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        driverClassName = "org.h2.Driver",
        username = "sa",
        password = "",
        maximumPoolSize = 2,
    )

    private fun avatarConfig() = AvatarConfig(
        bucket = "purr-avatars",
        endpoint = "http://localhost:9000",
        publicEndpoint = "http://localhost:9000",
        accessKey = "key",
        secretKey = "secret",
        region = "us-east-1",
        forcePathStyle = true,
        outputSizePixels = 512,
        maxSourceDimensionPixels = 8_192,
        maxSourcePixels = 40_000_000,
        jpegQualityPercent = 88,
        maxOutputBytes = 1_048_576,
        maxConcurrentProcessing = 2,
        cleanupEnabled = true,
        cleanupIntervalSeconds = 60,
        cleanupBatchSize = 100,
        cleanupMaxAttempts = 20,
        cleanupRetryBaseSeconds = 5,
        cleanupRetryMaxSeconds = 3_600,
        orphanGraceSeconds = 3_600,
    )

    private companion object {
        const val OBJECT_KEY = "avatars/user-a/00000000-0000-0000-0000-000000000001.jpg"
        const val ORPHAN_KEY = "avatars/user-a/00000000-0000-0000-0000-000000000002.jpg"
        const val REFERENCED_KEY = "avatars/user-a/00000000-0000-0000-0000-000000000003.jpg"
        const val RECENT_KEY = "avatars/user-a/00000000-0000-0000-0000-000000000004.jpg"
    }
}

private class FakeAvatarStore(
    private var failuresRemaining: Int,
    private val listedObjects: List<StoredAvatarObject> = emptyList(),
    private val onDelete: (String) -> Unit = {},
) : AvatarObjectDeleter, AvatarObjectCatalog {
    val deletedKeys = mutableListOf<String>()

    override fun delete(objectKey: String) {
        deletedKeys += objectKey
        onDelete(objectKey)
        if (failuresRemaining > 0) {
            failuresRemaining--
            error("storage unavailable")
        }
    }

    override fun listObjects(continuationToken: String?, maxKeys: Int) = StoredAvatarPage(listedObjects, null)
}

private class FakeCleanupTelemetry : AvatarCleanupTelemetry {
    val backlogs = mutableListOf<Pair<Long, Long>>()

    override fun recordCleanup(succeeded: Boolean) = Unit

    override fun recordBacklog(pendingTasks: Long, oldestTaskAgeSeconds: Long) {
        backlogs += pendingTasks to oldestTaskAgeSeconds
    }
}

private class FailingContinuationCatalog : AvatarObjectCatalog {
    val receivedTokens = mutableListOf<String?>()

    override fun listObjects(continuationToken: String?, maxKeys: Int): StoredAvatarPage {
        receivedTokens += continuationToken
        if (continuationToken != null) error("invalid continuation token")
        return StoredAvatarPage(emptyList(), "next")
    }
}
