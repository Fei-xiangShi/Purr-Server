package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

/** Durable provider callback inbox. One provider event id is processed at most once at a time. */
object WebhookInboxTable : Table("webhook_inbox") {
    val provider = varchar("provider", 64)
    val eventId = varchar("event_id", 256)
    val eventType = varchar("event_type", 128)
    val payload = text("payload")
    val payloadHash = varchar("payload_hash", 64)
    val receivedAtEpochMillis = long("received_at_epoch_millis")
    val availableAtEpochMillis = long("available_at_epoch_millis")
    val attemptCount = integer("attempt_count").default(0)
    val state = varchar("processing_state", 16)
    val leaseOwner = varchar("lease_owner", 128).nullable()
    val leaseUntilEpochMillis = long("lease_until_epoch_millis").nullable()
    val processedAtEpochMillis = long("processed_at_epoch_millis").nullable()
    val lastError = varchar("last_error", 2048).nullable()

    override val primaryKey = PrimaryKey(provider, eventId)
}
