package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object OutboxDispatchLocksTable : Table("outbox_dispatch_locks") {
    val lockName = varchar("lock_name", 64)
    val leaseOwner = varchar("lease_owner", 128).nullable()
    val leaseUntilEpochMillis = long("lease_until_epoch_millis").nullable()

    override val primaryKey = PrimaryKey(lockName)
}
