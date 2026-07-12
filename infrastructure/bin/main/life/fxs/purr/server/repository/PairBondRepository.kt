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
import org.jetbrains.exposed.sql.update

class PairBondRepository : PairStore {
    fun upsert(pairId: String, userAId: String, userBId: String, bondedAtEpochMillis: Long) {
        transaction {
            val exists = PairBondsTable.selectAll()
                .where { PairBondsTable.pairId eq pairId }
                .any()
            if (!exists) {
                PairBondsTable.insertIgnore {
                    it[PairBondsTable.pairId] = pairId
                    it[PairBondsTable.userAId] = userAId
                    it[PairBondsTable.userBId] = userBId
                    it[PairBondsTable.bondedAtEpochMillis] = bondedAtEpochMillis
                }
            } else {
                PairBondsTable.update({ PairBondsTable.pairId eq pairId }) {
                    it[PairBondsTable.userAId] = userAId
                    it[PairBondsTable.userBId] = userBId
                    it[PairBondsTable.bondedAtEpochMillis] = bondedAtEpochMillis
                }
            }
        }
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
