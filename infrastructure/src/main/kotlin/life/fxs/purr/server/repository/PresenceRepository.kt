package life.fxs.purr.server.repository

import life.fxs.purr.server.db.table.PresenceConnectionsTable
import life.fxs.purr.server.application.port.PresenceStore
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class PresenceRepository : PresenceStore {
    override fun connect(connectionId: String, userId: String, nowEpochMillis: Long) {
        transaction {
            deleteExpiredInCurrentTransaction(nowEpochMillis)
            PresenceConnectionsTable.insert {
                it[PresenceConnectionsTable.connectionId] = connectionId
                it[PresenceConnectionsTable.userId] = userId
                it[lastSeenEpochMillis] = nowEpochMillis
            }
        }
    }

    override fun heartbeat(connectionId: String, nowEpochMillis: Long) {
        transaction {
            PresenceConnectionsTable.update({ PresenceConnectionsTable.connectionId eq connectionId }) {
                it[lastSeenEpochMillis] = nowEpochMillis
            }
        }
    }

    override fun disconnect(connectionId: String) {
        transaction {
            PresenceConnectionsTable.deleteWhere { PresenceConnectionsTable.connectionId eq connectionId }
        }
    }

    override fun isOnline(userId: String, nowEpochMillis: Long): Boolean = transaction {
        deleteExpiredInCurrentTransaction(nowEpochMillis)
        PresenceConnectionsTable.selectAll()
            .where {
                (PresenceConnectionsTable.userId eq userId) and
                    (PresenceConnectionsTable.lastSeenEpochMillis greaterEq (nowEpochMillis - PRESENCE_TIMEOUT_MILLIS))
            }
            .limit(1)
            .any()
    }

    private fun deleteExpiredInCurrentTransaction(nowEpochMillis: Long) {
        val cutoff = nowEpochMillis - STALE_CONNECTION_RETENTION_MILLIS
        PresenceConnectionsTable.deleteWhere { lastSeenEpochMillis less cutoff }
    }

    private companion object {
        const val PRESENCE_TIMEOUT_MILLIS = 45_000L
        const val STALE_CONNECTION_RETENTION_MILLIS = 86_400_000L
    }
}
