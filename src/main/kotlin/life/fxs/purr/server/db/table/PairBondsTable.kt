package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object PairBondsTable : Table("pair_bonds") {
    val pairId = varchar("pair_id", 64)
    val userAId = varchar("user_a_id", 64).references(UsersTable.id)
    val userBId = varchar("user_b_id", 64).references(UsersTable.id)
    val bondedAtEpochMillis = long("bonded_at_epoch_millis")

    override val primaryKey = PrimaryKey(pairId)
}
