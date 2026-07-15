package life.fxs.purr.server.repository

import life.fxs.purr.server.db.table.PairBondsTable
import life.fxs.purr.server.application.port.PairRecord
import life.fxs.purr.server.application.port.PairStore
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class PairBondRepository : PairStore {
    fun insertIfAbsent(pairId: String, userAId: String, userBId: String, bondedAtEpochMillis: Long): Boolean =
        transaction {
            if (PairBondsTable.selectAll().where { PairBondsTable.pairId eq pairId }.any()) {
                return@transaction true
            }
            val inserted = PairBondsTable.insertIgnore {
                it[PairBondsTable.pairId] = pairId
                it[PairBondsTable.userAId] = userAId
                it[PairBondsTable.userBId] = userBId
                it[PairBondsTable.bondedAtEpochMillis] = bondedAtEpochMillis
            }.insertedCount == 1
            inserted || PairBondsTable.selectAll().where { PairBondsTable.pairId eq pairId }.any()
        }

    override fun findByUserId(userId: String): PairRecord? = transaction {
        PairBondsTable.selectAll()
            .where { (PairBondsTable.userAId eq userId) or (PairBondsTable.userBId eq userId) }
            .singleOrNull()
            ?.toPairRecord()
    }

    override fun findByPairId(pairId: String): PairRecord? = transaction {
        PairBondsTable.selectAll()
            .where { PairBondsTable.pairId eq pairId }
            .singleOrNull()
            ?.toPairRecord()
    }

    private fun ResultRow.toPairRecord(): PairRecord = PairRecord(
        pairId = this[PairBondsTable.pairId],
        userAId = this[PairBondsTable.userAId],
        userBId = this[PairBondsTable.userBId],
        bondedAtEpochMillis = this[PairBondsTable.bondedAtEpochMillis],
    )
}
