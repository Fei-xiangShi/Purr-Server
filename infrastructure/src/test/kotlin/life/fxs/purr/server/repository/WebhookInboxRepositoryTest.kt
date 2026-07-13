package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.WebhookInboxClaimState
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory

class WebhookInboxRepositoryTest {
    @Test
    fun `duplicate event is leased once and remains acknowledged after completion`() = withDatabase {
        val repository = WebhookInboxRepository()

        assertEquals(
            WebhookInboxClaimState.CLAIMED,
            repository.claimEvent(workerId = "worker-a").state,
        )
        assertEquals(
            WebhookInboxClaimState.IN_FLIGHT,
            repository.claimEvent(workerId = "worker-b").state,
        )
        assertTrue(
            repository.markProcessed(
                provider = PROVIDER,
                eventId = EVENT_ID,
                leaseOwner = "worker-a",
                processedAtEpochMillis = 1_500L,
            ),
        )
        assertEquals(
            WebhookInboxClaimState.PROCESSED,
            repository.claimEvent(workerId = "worker-c", nowEpochMillis = 2_000L).state,
        )
    }

    @Test
    fun `failed and expired claims can be retried by another worker`() = withDatabase {
        val repository = WebhookInboxRepository()
        repository.claimEvent(workerId = "worker-a")

        assertTrue(
            repository.releaseForRetry(
                provider = PROVIDER,
                eventId = EVENT_ID,
                leaseOwner = "worker-a",
                errorMessage = "temporary failure",
                availableAtEpochMillis = 1_100L,
            ),
        )
        assertEquals(
            WebhookInboxClaimState.CLAIMED,
            repository.claimEvent(workerId = "worker-b", nowEpochMillis = 1_100L).state,
        )

        assertEquals(
            WebhookInboxClaimState.CLAIMED,
            repository.claimEvent(workerId = "worker-c", nowEpochMillis = 2_500L).state,
        )
    }

    @Test
    fun `same provider event id with a different payload is rejected`() = withDatabase {
        val repository = WebhookInboxRepository()
        repository.claimEvent(workerId = "worker-a")

        assertFailsWith<ApplicationException> {
            repository.claim(
                provider = PROVIDER,
                eventId = EVENT_ID,
                eventType = "participant_left",
                payload = "different-payload",
                payloadHash = "different-hash",
                leaseOwner = "worker-b",
                nowEpochMillis = 1_100L,
                leaseUntilEpochMillis = 2_100L,
            )
        }
    }

    private fun WebhookInboxRepository.claimEvent(
        workerId: String,
        nowEpochMillis: Long = 1_000L,
    ) = claim(
        provider = PROVIDER,
        eventId = EVENT_ID,
        eventType = "participant_joined",
        payload = PAYLOAD,
        payloadHash = PAYLOAD_HASH,
        leaseOwner = workerId,
        nowEpochMillis = nowEpochMillis,
        leaseUntilEpochMillis = nowEpochMillis + 1_000L,
    )

    private fun withDatabase(block: () -> Unit) {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:webhook-inbox-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
        const val PROVIDER = "livekit"
        const val EVENT_ID = "event-1"
        const val PAYLOAD = "payload"
        const val PAYLOAD_HASH = "payload-hash"
    }
}
