package life.fxs.purr.server.recording

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.config.RecordingProvider
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository

class RecordingRetentionServiceTest {
    @Test
    fun `expired recording object is deleted while audit metadata is retained`() = withDatabase {
        val repository = seedExpiredRecording("recording-delete")
        val store = RecordingObjectStore { objectKey -> assertEquals("recordings/call-1/audio.ogg", objectKey) }
        val service = RecordingRetentionService(retentionConfig(), repository, store)

        val summary = service.cleanupOnce(NOW)

        assertEquals(RecordingCleanupSummary(1, 1, 1, 0), summary)
        val stored = assertNotNull(repository.findByRecordingId("recording-delete"))
        assertEquals(RecordingStatus.DELETED, stored.status)
        assertNull(stored.objectKey)
        assertEquals(NOW.toEpochMilli(), stored.deletedAtEpochMillis)
        assertEquals(1, stored.deletionAttempts)
        service.close()
    }

    @Test
    fun `failed object deletion is retried only up to configured limit`() = withDatabase {
        val repository = seedExpiredRecording("recording-failure")
        var calls = 0
        val store = RecordingObjectStore {
            calls++
            error("storage unavailable")
        }
        val service = RecordingRetentionService(retentionConfig(), repository, store)

        assertEquals(1, service.cleanupOnce(NOW).failed)
        assertEquals(1, service.cleanupOnce(NOW.plusSeconds(61)).failed)
        assertEquals(0, service.cleanupOnce(NOW.plusSeconds(122)).candidates)

        val stored = assertNotNull(repository.findByRecordingId("recording-failure"))
        assertEquals(RecordingStatus.STOPPED, stored.status)
        assertEquals(2, stored.deletionAttempts)
        assertEquals("storage unavailable", stored.deletionErrorMessage)
        assertEquals(2, calls)
        service.close()
    }

    @Test
    fun `close waits for an active deletion before dependent resources are released`() = withDatabase {
        val repository = seedExpiredRecording("recording-close")
        val deletionStarted = CountDownLatch(1)
        val allowDeletionToFinish = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val store = RecordingObjectStore {
            deletionStarted.countDown()
            assertTrue(allowDeletionToFinish.await(5, TimeUnit.SECONDS))
        }
        val service = RecordingRetentionService(retentionConfig(), repository, store)
        service.start()
        assertTrue(deletionStarted.await(5, TimeUnit.SECONDS))

        val closeThread = thread(name = "recording-retention-close") {
            service.close()
            closeFinished.countDown()
        }
        assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))

        allowDeletionToFinish.countDown()
        assertTrue(closeFinished.await(5, TimeUnit.SECONDS))
        closeThread.join()
        assertEquals(RecordingStatus.DELETED, repository.findByRecordingId("recording-close")?.status)
    }

    private fun seedExpiredRecording(recordingId: String): CallRecordingRepository {
        val users = UserRepository()
        val pairs = PairBondRepository()
        val calls = CallSessionRepository()
        val recordings = CallRecordingRepository()
        users.upsert("user-a", "user-a", "pass-a", "A", null)
        users.upsert("user-b", "user-b", "pass-b", "B", null)
        pairs.upsert("pair-1", "user-a", "user-b", 1L)
        calls.upsert(
            CallRecord(
                callId = "call-1",
                pairId = "pair-1",
                roomName = "room-1",
                createdByUserId = "user-a",
                startedAtEpochMillis = OLD.toEpochMilli(),
                updatedAtEpochMillis = OLD.toEpochMilli(),
                endedAtEpochMillis = OLD.plusSeconds(60).toEpochMilli(),
                state = CallState.ENDED,
                recordingStatus = RecordingStatus.STOPPED,
                recordingId = recordingId,
            ),
        )
        recordings.updateCurrent(
            "call-1",
            ProviderRecordingResult(
                status = RecordingStatus.STOPPED,
                recordingId = recordingId,
                updatedAtEpochMillis = OLD.plusSeconds(60).toEpochMilli(),
                objectKey = "recordings/call-1/audio.ogg",
                startedAtEpochMillis = OLD.toEpochMilli(),
                endedAtEpochMillis = OLD.plusSeconds(60).toEpochMilli(),
                durationMillis = 60_000L,
            ),
        )
        return recordings
    }

    private fun retentionConfig() = RecordingConfig(
        enabled = true,
        provider = RecordingProvider.LIVEKIT,
        idPrefix = "rec",
        filePrefix = "recordings",
        bucket = "bucket",
        endpoint = "http://minio:9000",
        publicEndpoint = "https://storage.example",
        accessKey = "key",
        secretKey = "secret",
        region = "us-east-1",
        forcePathStyle = true,
        recoveryEnabled = true,
        recoveryIntervalSeconds = 30,
        recoveryStaleAfterSeconds = 90,
        recoveryMaxAttempts = 5,
        downloadUrlTtlSeconds = 300,
        consentPolicyVersion = "test-v1",
        cleanupEnabled = true,
        retentionDays = 30,
        cleanupIntervalSeconds = 60,
        cleanupBatchSize = 100,
        cleanupMaxAttempts = 2,
    )

    private fun withDatabase(block: () -> Unit) {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:recording-retention-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()
        try {
            block()
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-10T12:00:00Z")
        val OLD: Instant = NOW.minusSeconds(31L * 86_400L)
    }
}
