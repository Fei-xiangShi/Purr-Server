package life.fxs.purr.server.repository

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.WebhookInboxClaim
import life.fxs.purr.server.application.port.WebhookInboxClaimState
import life.fxs.purr.server.application.port.WebhookInboxStore
import life.fxs.purr.server.db.table.WebhookInboxTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * JDBC/Exposed inbox implementation. Claims use a row lock and a lease so a
 * process crash leaves the event retryable after the lease expires.
 */
class WebhookInboxRepository : WebhookInboxStore {
    override fun claim(
        provider: String,
        eventId: String,
        eventType: String,
        payload: String,
        payloadHash: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): WebhookInboxClaim = transaction {
        WebhookInboxTable.insertIgnore {
            it[WebhookInboxTable.provider] = provider
            it[WebhookInboxTable.eventId] = eventId
            it[WebhookInboxTable.eventType] = eventType
            it[WebhookInboxTable.payload] = payload
            it[WebhookInboxTable.payloadHash] = payloadHash
            it[WebhookInboxTable.receivedAtEpochMillis] = nowEpochMillis
            it[WebhookInboxTable.availableAtEpochMillis] = nowEpochMillis
            it[WebhookInboxTable.attemptCount] = 0
            it[WebhookInboxTable.state] = STATE_RECEIVED
            it[WebhookInboxTable.leaseOwner] = null
            it[WebhookInboxTable.leaseUntilEpochMillis] = null
            it[WebhookInboxTable.processedAtEpochMillis] = null
            it[WebhookInboxTable.lastError] = null
        }

        val existing = WebhookInboxTable.selectAll()
            .where {
                (WebhookInboxTable.provider eq provider) and
                    (WebhookInboxTable.eventId eq eventId)
            }
            .forUpdate()
            .single()

        if (existing[WebhookInboxTable.payloadHash] != payloadHash) {
            throw ApplicationException(
                ApplicationError.INVALID_ARGUMENT,
                "Webhook event id was already received with a different payload",
            )
        }

        when (existing[WebhookInboxTable.state]) {
            STATE_PROCESSED -> WebhookInboxClaim(WebhookInboxClaimState.PROCESSED)
            STATE_PROCESSING -> {
                val leaseUntil = existing[WebhookInboxTable.leaseUntilEpochMillis]
                if (leaseUntil != null && leaseUntil > nowEpochMillis) {
                    WebhookInboxClaim(WebhookInboxClaimState.IN_FLIGHT)
                } else {
                    updateClaim(
                        provider = provider,
                        eventId = eventId,
                        leaseOwner = leaseOwner,
                        nowEpochMillis = nowEpochMillis,
                        leaseUntilEpochMillis = leaseUntilEpochMillis,
                    )
                    WebhookInboxClaim(WebhookInboxClaimState.CLAIMED)
                }
            }
            else -> {
                updateClaim(
                    provider = provider,
                    eventId = eventId,
                    leaseOwner = leaseOwner,
                    nowEpochMillis = nowEpochMillis,
                    leaseUntilEpochMillis = leaseUntilEpochMillis,
                )
                WebhookInboxClaim(WebhookInboxClaimState.CLAIMED)
            }
        }
    }

    override fun markProcessed(
        provider: String,
        eventId: String,
        leaseOwner: String,
        processedAtEpochMillis: Long,
    ): Boolean = transaction {
        WebhookInboxTable.update(
            where = {
                (WebhookInboxTable.provider eq provider) and
                    (WebhookInboxTable.eventId eq eventId) and
                    (WebhookInboxTable.state eq STATE_PROCESSING) and
                    (WebhookInboxTable.leaseOwner eq leaseOwner)
            },
        ) {
            it[WebhookInboxTable.state] = STATE_PROCESSED
            it[WebhookInboxTable.leaseOwner] = null
            it[WebhookInboxTable.leaseUntilEpochMillis] = null
            it[WebhookInboxTable.processedAtEpochMillis] = processedAtEpochMillis
            it[WebhookInboxTable.lastError] = null
        } == 1
    }

    override fun releaseForRetry(
        provider: String,
        eventId: String,
        leaseOwner: String,
        errorMessage: String,
        availableAtEpochMillis: Long,
    ): Boolean = transaction {
        WebhookInboxTable.update(
            where = {
                (WebhookInboxTable.provider eq provider) and
                    (WebhookInboxTable.eventId eq eventId) and
                    (WebhookInboxTable.state eq STATE_PROCESSING) and
                    (WebhookInboxTable.leaseOwner eq leaseOwner)
            },
        ) {
            it[WebhookInboxTable.state] = STATE_RECEIVED
            it[WebhookInboxTable.leaseOwner] = null
            it[WebhookInboxTable.leaseUntilEpochMillis] = null
            it[WebhookInboxTable.availableAtEpochMillis] = availableAtEpochMillis
            it[WebhookInboxTable.lastError] = errorMessage.take(MAX_ERROR_LENGTH)
        } == 1
    }

    private fun updateClaim(
        provider: String,
        eventId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ) {
        val updated = WebhookInboxTable.update(
            where = {
                (WebhookInboxTable.provider eq provider) and
                    (WebhookInboxTable.eventId eq eventId)
            },
        ) {
            it[WebhookInboxTable.state] = STATE_PROCESSING
            it[WebhookInboxTable.leaseOwner] = leaseOwner
            it[WebhookInboxTable.leaseUntilEpochMillis] = leaseUntilEpochMillis
            it[WebhookInboxTable.availableAtEpochMillis] = nowEpochMillis
            it[WebhookInboxTable.attemptCount] = WebhookInboxTable.attemptCount + 1
            it[WebhookInboxTable.lastError] = null
        }
        check(updated == 1) { "Webhook inbox row disappeared while claiming $provider/$eventId" }
    }

    private companion object {
        const val STATE_RECEIVED = "RECEIVED"
        const val STATE_PROCESSING = "PROCESSING"
        const val STATE_PROCESSED = "PROCESSED"
        const val MAX_ERROR_LENGTH = 2_048
    }
}
