package life.fxs.purr.server.repository

import life.fxs.purr.server.application.port.PushDeviceRecord
import life.fxs.purr.server.application.port.PushDeviceStore
import life.fxs.purr.server.application.port.PushProvider
import life.fxs.purr.server.db.table.PushDevicesTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class PushDeviceRepository : PushDeviceStore {
    override fun upsert(device: PushDeviceRecord) {
        transaction {
            PushDevicesTable.deleteWhere {
                (PushDevicesTable.provider eq device.provider.name) and
                    (PushDevicesTable.token eq device.token) and
                    (PushDevicesTable.installationId neq device.installationId)
            }
            val updated = PushDevicesTable.update(
                where = { PushDevicesTable.installationId eq device.installationId },
            ) {
                it[userId] = device.userId
                it[sessionId] = device.sessionId
                it[provider] = device.provider.name
                it[token] = device.token
                it[updatedAtEpochMillis] = device.updatedAtEpochMillis
                it[disabledAtEpochMillis] = null
            }
            if (updated == 0) {
                PushDevicesTable.insert {
                    it[installationId] = device.installationId
                    it[userId] = device.userId
                    it[sessionId] = device.sessionId
                    it[provider] = device.provider.name
                    it[token] = device.token
                    it[createdAtEpochMillis] = device.createdAtEpochMillis
                    it[updatedAtEpochMillis] = device.updatedAtEpochMillis
                    it[disabledAtEpochMillis] = null
                }
            }
        }
    }

    override fun remove(userId: String, installationId: String): Boolean = transaction {
        PushDevicesTable.deleteWhere {
            (PushDevicesTable.userId eq userId) and
                (PushDevicesTable.installationId eq installationId)
        } == 1
    }

    override fun findActiveByUserId(userId: String): List<PushDeviceRecord> = transaction {
        PushDevicesTable.selectAll()
            .where {
                (PushDevicesTable.userId eq userId) and PushDevicesTable.disabledAtEpochMillis.isNull()
            }
            .orderBy(PushDevicesTable.updatedAtEpochMillis to SortOrder.DESC)
            .map(ResultRow::toPushDeviceRecord)
    }

    override fun disable(provider: PushProvider, token: String, disabledAtEpochMillis: Long): Boolean = transaction {
        PushDevicesTable.update(
            where = {
                (PushDevicesTable.provider eq provider.name) and
                    (PushDevicesTable.token eq token) and
                    PushDevicesTable.disabledAtEpochMillis.isNull()
            },
        ) {
            it[PushDevicesTable.disabledAtEpochMillis] = disabledAtEpochMillis
            it[updatedAtEpochMillis] = disabledAtEpochMillis
        } == 1
    }
}

private fun ResultRow.toPushDeviceRecord() = PushDeviceRecord(
    installationId = this[PushDevicesTable.installationId],
    userId = this[PushDevicesTable.userId],
    sessionId = this[PushDevicesTable.sessionId],
    provider = PushProvider.valueOf(this[PushDevicesTable.provider]),
    token = this[PushDevicesTable.token],
    createdAtEpochMillis = this[PushDevicesTable.createdAtEpochMillis],
    updatedAtEpochMillis = this[PushDevicesTable.updatedAtEpochMillis],
    disabledAtEpochMillis = this[PushDevicesTable.disabledAtEpochMillis],
)
