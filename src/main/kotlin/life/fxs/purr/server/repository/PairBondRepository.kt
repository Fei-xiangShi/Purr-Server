package life.fxs.purr.server.repository

import life.fxs.purr.server.db.table.PairBondsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class PairBondRepository {
    fun upsert(pairId: String, userAId: String, userBId: String, bondedAtEpochMillis: Long) {
        transaction {
            PairBondsTable.insertIgnore {
                it[PairBondsTable.pairId] = pairId
                it[PairBondsTable.userAId] = userAId
                it[PairBondsTable.userBId] = userBId
                it[PairBondsTable.bondedAtEpochMillis] = bondedAtEpochMillis
            }
        }
    }

    fun findByUserId(userId: String): StoredPairBond? = transaction {
        PairBondsTable.selectAll()
            .where { (PairBondsTable.userAId eq userId) or (PairBondsTable.userBId eq userId) }
            .singleOrNull()
            ?.toStoredPairBond()
    }

    fun findByPairId(pairId: String): StoredPairBond? = transaction {
        PairBondsTable.selectAll()
            .where { PairBondsTable.pairId eq pairId }
            .singleOrNull()
            ?.toStoredPairBond()
    }

    private fun ResultRow.toStoredPairBond(): StoredPairBond = StoredPairBond(
        pairId = this[PairBondsTable.pairId],
        userAId = this[PairBondsTable.userAId],
        userBId = this[PairBondsTable.userBId],
        bondedAtEpochMillis = this[PairBondsTable.bondedAtEpochMillis],
    )
}

data class StoredPairBond(
    val pairId: String,
    val userAId: String,
    val userBId: String,
    val bondedAtEpochMillis: Long,
)
