package life.fxs.purr.server.realtime

import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.db.table.OutboxEventsTable
import life.fxs.purr.server.db.table.OutboxDispatchLocksTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class OutboxRepository(
    private val json: Json = realtimeJson,
    private val eventIdProvider: () -> String = { UUID.randomUUID().toString() },
) : RealtimeOutbox {
    fun acquireDispatcherLease(
        workerId: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): Boolean = transaction {
        OutboxDispatchLocksTable.update(
            where = {
                (OutboxDispatchLocksTable.lockName eq REALTIME_LOCK) and
                    (
                        OutboxDispatchLocksTable.leaseUntilEpochMillis.isNull() or
                            (OutboxDispatchLocksTable.leaseUntilEpochMillis less nowEpochMillis) or
                            (OutboxDispatchLocksTable.leaseOwner eq workerId)
                        )
            },
        ) {
            it[leaseOwner] = workerId
            it[OutboxDispatchLocksTable.leaseUntilEpochMillis] = leaseUntilEpochMillis
        } == 1
    }

    fun releaseDispatcherLease(workerId: String) {
        transaction {
            OutboxDispatchLocksTable.update(
                where = {
                    (OutboxDispatchLocksTable.lockName eq REALTIME_LOCK) and
                        (OutboxDispatchLocksTable.leaseOwner eq workerId)
                },
            ) {
                it[leaseOwner] = null
                it[leaseUntilEpochMillis] = null
            }
        }
    }

    override fun enqueue(
        recipientUserId: String,
        event: RealtimeEvent,
        occurredAtEpochMillis: Long,
    ) {
        transaction {
            OutboxEventsTable.insert {
                it[eventId] = eventIdProvider()
                it[OutboxEventsTable.recipientUserId] = recipientUserId
                it[eventType] = event.type
                it[payload] = json.encodeToString(event.toPayload())
                it[OutboxEventsTable.occurredAtEpochMillis] = occurredAtEpochMillis
                it[availableAtEpochMillis] = occurredAtEpochMillis
                it[attemptCount] = 0
                it[leaseOwner] = null
                it[leaseUntilEpochMillis] = null
                it[publishedAtEpochMillis] = null
                it[lastError] = null
            }
        }
    }

    fun claimBatch(
        workerId: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
        maxAttempts: Int,
        limit: Int,
    ): List<OutboxRecord> = transaction {
        val candidates = OutboxEventsTable.selectAll()
            .where {
                OutboxEventsTable.publishedAtEpochMillis.isNull() and
                    (OutboxEventsTable.availableAtEpochMillis lessEq nowEpochMillis) and
                    (OutboxEventsTable.attemptCount less maxAttempts) and
                    (
                        OutboxEventsTable.leaseUntilEpochMillis.isNull() or
                            (OutboxEventsTable.leaseUntilEpochMillis less nowEpochMillis)
                        )
            }
            .orderBy(OutboxEventsTable.occurredAtEpochMillis to SortOrder.ASC)
            .limit(limit)
            .map { it[OutboxEventsTable.eventId] }

        candidates.mapNotNull { candidateId ->
            val updated = OutboxEventsTable.update(
                where = {
                    (OutboxEventsTable.eventId eq candidateId) and
                        OutboxEventsTable.publishedAtEpochMillis.isNull() and
                        (OutboxEventsTable.availableAtEpochMillis lessEq nowEpochMillis) and
                        (OutboxEventsTable.attemptCount less maxAttempts) and
                        (
                            OutboxEventsTable.leaseUntilEpochMillis.isNull() or
                                (OutboxEventsTable.leaseUntilEpochMillis less nowEpochMillis)
                            )
                },
            ) {
                it[leaseOwner] = workerId
                it[OutboxEventsTable.leaseUntilEpochMillis] = leaseUntilEpochMillis
                it[attemptCount] = OutboxEventsTable.attemptCount + 1
            }
            if (updated != 1) {
                null
            } else {
                OutboxEventsTable.selectAll()
                    .where { OutboxEventsTable.eventId eq candidateId }
                    .single()
                    .toOutboxRecord(json)
            }
        }
    }

    fun markPublished(eventId: String, workerId: String, publishedAtEpochMillis: Long): Boolean = transaction {
        OutboxEventsTable.update(
            where = {
                (OutboxEventsTable.eventId eq eventId) and
                    (OutboxEventsTable.leaseOwner eq workerId) and
                    OutboxEventsTable.publishedAtEpochMillis.isNull()
            },
        ) {
            it[OutboxEventsTable.publishedAtEpochMillis] = publishedAtEpochMillis
            it[leaseOwner] = null
            it[leaseUntilEpochMillis] = null
            it[lastError] = null
        } == 1
    }

    fun markFailed(
        eventId: String,
        workerId: String,
        availableAtEpochMillis: Long,
        errorMessage: String,
    ): Boolean = transaction {
        OutboxEventsTable.update(
            where = {
                (OutboxEventsTable.eventId eq eventId) and
                    (OutboxEventsTable.leaseOwner eq workerId) and
                    OutboxEventsTable.publishedAtEpochMillis.isNull()
            },
        ) {
            it[leaseOwner] = null
            it[leaseUntilEpochMillis] = null
            it[OutboxEventsTable.availableAtEpochMillis] = availableAtEpochMillis
            it[lastError] = errorMessage.take(MAX_ERROR_LENGTH)
        } == 1
    }

    internal fun find(eventId: String): OutboxRecord? = transaction {
        OutboxEventsTable.selectAll()
            .where { OutboxEventsTable.eventId eq eventId }
            .singleOrNull()
            ?.toOutboxRecord(json)
    }
}

data class OutboxRecord(
    val eventId: String,
    val recipientUserId: String,
    val event: RealtimeEvent,
    val occurredAtEpochMillis: Long,
    val availableAtEpochMillis: Long,
    val attemptCount: Int,
    val publishedAtEpochMillis: Long?,
    val lastError: String?,
)

private fun ResultRow.toOutboxRecord(json: Json) = OutboxRecord(
    eventId = this[OutboxEventsTable.eventId],
    recipientUserId = this[OutboxEventsTable.recipientUserId],
    event = json.decodeFromString<RealtimeEventPayload>(this[OutboxEventsTable.payload]).toApplicationEvent(),
    occurredAtEpochMillis = this[OutboxEventsTable.occurredAtEpochMillis],
    availableAtEpochMillis = this[OutboxEventsTable.availableAtEpochMillis],
    attemptCount = this[OutboxEventsTable.attemptCount],
    publishedAtEpochMillis = this[OutboxEventsTable.publishedAtEpochMillis],
    lastError = this[OutboxEventsTable.lastError],
)

private const val MAX_ERROR_LENGTH = 2_048
private const val REALTIME_LOCK = "realtime"
