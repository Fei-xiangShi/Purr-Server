package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object OutboxEventsTable : Table("outbox_events") {
    val eventId = varchar("event_id", 64)
    val recipientUserId = varchar("recipient_user_id", 64).references(UsersTable.id)
    val eventType = varchar("event_type", 64)
    val payload = text("payload")
    val occurredAtEpochMillis = long("occurred_at_epoch_millis")
    val availableAtEpochMillis = long("available_at_epoch_millis")
    val attemptCount = integer("attempt_count").default(0)
    val leaseOwner = varchar("lease_owner", 128).nullable()
    val leaseUntilEpochMillis = long("lease_until_epoch_millis").nullable()
    val publishedAtEpochMillis = long("published_at_epoch_millis").nullable()
    val lastError = varchar("last_error", 2048).nullable()

    override val primaryKey = PrimaryKey(eventId)
}
