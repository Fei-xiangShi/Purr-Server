package life.fxs.purr.server.avatar

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import life.fxs.purr.server.config.AvatarConfig

class AvatarCleanupWorkerLifecycleTest {
    @Test
    fun `starts a cleanup pass and closes without leaving work running`() {
        val invoked = CountDownLatch(1)
        val worker = AvatarCleanupWorker(
            config = config(),
            cleanupPass = AvatarCleanupPass {
                invoked.countDown()
                AvatarCleanupSummary(0, 0, 0)
            },
        )

        worker.start()

        assertTrue(invoked.await(5, TimeUnit.SECONDS))
        worker.close()
    }

    private fun config() = AvatarConfig(
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
}
