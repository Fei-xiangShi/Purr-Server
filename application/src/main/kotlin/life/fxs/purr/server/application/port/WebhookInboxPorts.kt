package life.fxs.purr.server.application.port

/**
 * Durable at-least-once inbox for provider callbacks.
 *
 * The inbox is intentionally a port: provider adapters only verify/map an
 * event and claim it, while the infrastructure implementation owns the
 * unique constraint, lease and persistence details.
 */
interface WebhookInboxStore {
    fun claim(
        provider: String,
        eventId: String,
        eventType: String,
        payload: String,
        payloadHash: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): WebhookInboxClaim

    fun markProcessed(
        provider: String,
        eventId: String,
        leaseOwner: String,
        processedAtEpochMillis: Long,
    ): Boolean

    /** Release a claim after a failed application attempt so provider retry is immediate. */
    fun releaseForRetry(
        provider: String,
        eventId: String,
        leaseOwner: String,
        errorMessage: String,
        availableAtEpochMillis: Long,
    ): Boolean
}

enum class WebhookInboxClaimState {
    CLAIMED,
    PROCESSED,
    IN_FLIGHT,
}

data class WebhookInboxClaim(
    val state: WebhookInboxClaimState,
)
