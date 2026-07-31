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
        assertEquals("drive-recording-delete", stored.driveFileId)
        service.close()
    }

    @Test
    fun `failed object deletion remains eligible for future daily passes`() = withDatabase {
        val repository = seedExpiredRecording("recording-failure")
        var calls = 0
        val store = RecordingObjectStore {
            calls++
            error("storage unavailable")
        }
        val service = RecordingRetentionService(retentionConfig(), repository, store)

        assertEquals(1, service.cleanupOnce(NOW).failed)
        assertEquals(1, service.cleanupOnce(NOW.plusSeconds(61)).failed)
        assertEquals(1, service.cleanupOnce(NOW.plusSeconds(122)).failed)

        val stored = assertNotNull(repository.findByRecordingId("recording-failure"))
        assertEquals(RecordingStatus.STOPPED, stored.status)
        assertEquals(3, stored.deletionAttempts)
        assertEquals("storage unavailable", stored.deletionErrorMessage)
        assertEquals(3, calls)
        service.close()
    }

    @Test
    fun `expired recording without confirmed Drive upload is retained`() = withDatabase {
        val repository = seedExpiredRecording("recording-pending", archived = false)
        var deletionCalls = 0
        val service = RecordingRetentionService(
            retentionConfig(),
            repository,
            RecordingObjectStore { deletionCalls++ },
        )

        assertEquals(RecordingCleanupSummary(0, 0, 0, 0), service.cleanupOnce(NOW))
        assertEquals(0, deletionCalls)
        assertNotNull(repository.findByRecordingId("recording-pending")?.objectKey)
        service.close()
    }

    @Test
    fun `scheduled pass drains every eligible batch`() = withDatabase {
        seedExpiredRecording("recording-batch-a", callId = "call-batch-a")
        val repository = seedExpiredRecording("recording-batch-b", callId = "call-batch-b")
        var deletionCalls = 0
        val service = RecordingRetentionService(
            retentionConfig().copy(cleanupBatchSize = 1),
            repository,
            RecordingObjectStore { deletionCalls++ },
        )

        assertEquals(RecordingCleanupSummary(2, 2, 2, 0), service.cleanupScheduledPass(NOW))
        assertEquals(2, deletionCalls)
        service.close()
    }

    @Test
    fun `deletion lease excludes concurrent workers and expires safely`() = withDatabase {
        val repository = seedExpiredRecording("recording-lease")
        val candidate = repository.findRetentionCandidates(
            endedBeforeEpochMillis = NOW.minusSeconds(7L * 86_400L).toEpochMilli(),
            nowEpochMillis = NOW.toEpochMilli(),
            limit = 1,
        ).single()

        val first = repository.claimDeletion(
            candidate,
            workerId = "worker-a",
            attemptedAtEpochMillis = NOW.toEpochMilli(),
            leaseUntilEpochMillis = NOW.plusSeconds(60).toEpochMilli(),
        )
        val concurrent = repository.findRetentionCandidates(
            endedBeforeEpochMillis = NOW.minusSeconds(7L * 86_400L).toEpochMilli(),
            nowEpochMillis = NOW.plusSeconds(1).toEpochMilli(),
            limit = 1,
        )
        val recoveredCandidate = repository.findRetentionCandidates(
            endedBeforeEpochMillis = NOW.minusSeconds(7L * 86_400L).toEpochMilli(),
            nowEpochMillis = NOW.plusSeconds(61).toEpochMilli(),
            limit = 1,
        ).single()
        val recovered = repository.claimDeletion(
            recoveredCandidate,
            workerId = "worker-b",
            attemptedAtEpochMillis = NOW.plusSeconds(61).toEpochMilli(),
            leaseUntilEpochMillis = NOW.plusSeconds(121).toEpochMilli(),
        )

        assertNotNull(first)
        assertTrue(concurrent.isEmpty())
        assertEquals("worker-b", assertNotNull(recovered).deletionLeaseOwner)
        assertEquals(2, recovered.deletionAttempts)
    }

    @Test
    fun `next cleanup is five PM in Shanghai and rolls to tomorrow at the boundary`() = withDatabase {
        val service = RecordingRetentionService(
            retentionConfig(),
            CallRecordingRepository(),
            RecordingObjectStore { },
        )

        assertEquals(
            Instant.parse("2026-07-10T09:00:00Z"),
            service.nextCleanupInstant(Instant.parse("2026-07-10T08:59:00Z")),
        )
        assertEquals(
            Instant.parse("2026-07-11T09:00:00Z"),
            service.nextCleanupInstant(Instant.parse("2026-07-10T09:00:00Z")),
        )
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
        val service = RecordingRetentionService(
            retentionConfig(),
            repository,
            store,
            nowProvider = { NOW },
            delayProvider = { },
        )
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

    private fun seedExpiredRecording(
        recordingId: String,
        archived: Boolean = true,
        callId: String = "call-1",
    ): CallRecordingRepository {
        val users = UserRepository()
        val pairs = PairBondRepository()
        val calls = CallSessionRepository()
        val recordings = CallRecordingRepository()
        users.insertIfAbsent("user-a", "user-a", "pass-a", "A", null)
        users.insertIfAbsent("user-b", "user-b", "pass-b", "B", null)
        pairs.insertIfAbsent("pair-1", "user-a", "user-b", 1L)
        calls.upsert(
            CallRecord(
                callId = callId,
                pairId = "pair-1",
                roomName = "room-$callId",
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
            callId,
            ProviderRecordingResult(
                status = RecordingStatus.STOPPED,
                recordingId = recordingId,
                updatedAtEpochMillis = OLD.plusSeconds(60).toEpochMilli(),
                objectKey = "recordings/$callId/audio.ogg",
                startedAtEpochMillis = OLD.toEpochMilli(),
                endedAtEpochMillis = OLD.plusSeconds(60).toEpochMilli(),
                durationMillis = 60_000L,
            ),
        )
        if (archived) {
            val claimed = assertNotNull(
                recordings.claimNextDriveUpload(
                    workerId = "archive-seed",
                    nowEpochMillis = NOW.toEpochMilli(),
                    leaseUntilEpochMillis = NOW.plusSeconds(60).toEpochMilli(),
                ),
            )
            assertEquals(recordingId, claimed.recordingId)
            assertTrue(
                recordings.markDriveUploaded(
                    recordingId = recordingId,
                    workerId = "archive-seed",
                    driveFileId = "drive-$recordingId",
                    uploadedAtEpochMillis = NOW.toEpochMilli(),
                ),
            )
        }
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
        retentionDays = 7,
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
        val OLD: Instant = NOW.minusSeconds(8L * 86_400L)
    }
}
