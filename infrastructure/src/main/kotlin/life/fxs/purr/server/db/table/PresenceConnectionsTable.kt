package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object PresenceConnectionsTable : Table("presence_connections") {
    val connectionId = varchar("connection_id", 64)
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val lastSeenEpochMillis = long("last_seen_epoch_millis")

    override val primaryKey = PrimaryKey(connectionId)
}
