package life.fxs.purr.server.repository

import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.application.port.ScreenShareStore
import life.fxs.purr.server.application.port.ScreenShareTransition
import life.fxs.purr.server.db.table.CallSessionsTable
import life.fxs.purr.server.db.table.ScreenSharesTable
import life.fxs.purr.server.model.ScreenShareSource
import life.fxs.purr.server.model.ScreenShareStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ScreenShareRepository : ScreenShareStore {
    override fun createIfAbsent(record: ScreenShareRecord): Boolean = transaction {
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.callId eq record.callId }
            .forUpdate()
            .single()
        if (findActiveByCallInCurrentTransaction(record.callId) != null) return@transaction false
        ScreenSharesTable.insert {
            it[shareId] = record.shareId
            it[callId] = record.callId
            it[activeCallId] = record.callId
            it[ownerUserId] = record.ownerUserId
            it[sourceType] = record.source.wireValue
            it[mediaPath] = record.mediaPath
            it[status] = record.status.wireValue
            it[createdAtEpochMillis] = record.createdAtEpochMillis
            it[updatedAtEpochMillis] = record.updatedAtEpochMillis
            it[expiresAtEpochMillis] = record.expiresAtEpochMillis
            it[liveAtEpochMillis] = record.liveAtEpochMillis
            it[stoppedAtEpochMillis] = record.stoppedAtEpochMillis
            it[providerSourceType] = record.providerSourceType
            it[providerSourceId] = record.providerSourceId
            it[missingSnapshotCount] = record.missingSnapshotCount
            it[providerCleanedAtEpochMillis] = record.providerCleanedAtEpochMillis
            it[lastError] = record.lastError
        }
        true
    }

    override fun findByShareId(shareId: String): ScreenShareRecord? = transaction {
        findByShareIdInCurrentTransaction(shareId)
    }

    override fun findByMediaPath(mediaPath: String): ScreenShareRecord? = transaction {
        ScreenSharesTable.selectAll()
            .where { ScreenSharesTable.mediaPath eq mediaPath }
            .singleOrNull()
            ?.toRecord()
    }

    override fun findCurrentByCallId(callId: String): ScreenShareRecord? = transaction {
        ScreenSharesTable.selectAll()
            .where { ScreenSharesTable.callId eq callId }
            .orderBy(ScreenSharesTable.createdAtEpochMillis to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toRecord()
    }

    override fun findReconciliationCandidates(nowEpochMillis: Long, limit: Int): List<ScreenShareRecord> = transaction {
        ScreenSharesTable.selectAll()
            .where {
                (ScreenSharesTable.status inList openStatusValues) or
                    (
                        ScreenSharesTable.providerCleanedAtEpochMillis.isNull() and
                            (ScreenSharesTable.status inList terminalCleanupStatusValues)
                        )
            }
            .orderBy(ScreenSharesTable.updatedAtEpochMillis to SortOrder.ASC)
            .limit(limit)
            .map { it.toRecord() }
    }

    override fun markLive(
        shareId: String,
        providerSourceType: String?,
        providerSourceId: String?,
        observedAtEpochMillis: Long,
    ): ScreenShareTransition? = transaction {
        val current = findByShareIdForUpdate(shareId) ?: return@transaction null
        if (current.status != ScreenShareStatus.AUTHORIZED && current.status != ScreenShareStatus.LIVE) {
            return@transaction ScreenShareTransition(current, changed = false)
        }
        ScreenSharesTable.update({ ScreenSharesTable.shareId eq shareId }) {
            it[status] = ScreenShareStatus.LIVE.wireValue
            it[liveAtEpochMillis] = current.liveAtEpochMillis ?: observedAtEpochMillis
            it[ScreenSharesTable.providerSourceType] = providerSourceType
            it[ScreenSharesTable.providerSourceId] = providerSourceId
            it[missingSnapshotCount] = 0
            it[updatedAtEpochMillis] = observedMillis(current, observedAtEpochMillis)
            it[lastError] = null
        }
        ScreenShareTransition(
            record = checkNotNull(findByShareIdInCurrentTransaction(shareId)),
            changed = current.status != ScreenShareStatus.LIVE,
        )
    }

    override fun observePresent(
        shareId: String,
        providerSourceType: String?,
        providerSourceId: String?,
        observedAtEpochMillis: Long,
    ): ScreenShareRecord? = transaction {
        ScreenSharesTable.update({ ScreenSharesTable.shareId eq shareId }) {
            it[ScreenSharesTable.providerSourceType] = providerSourceType
            it[ScreenSharesTable.providerSourceId] = providerSourceId
            it[missingSnapshotCount] = 0
            it[updatedAtEpochMillis] = observedAtEpochMillis
            it[lastError] = null
        }
        findByShareIdInCurrentTransaction(shareId)
    }

    override fun observeMissing(shareId: String, observedAtEpochMillis: Long): ScreenShareRecord? = transaction {
        val current = findByShareIdForUpdate(shareId) ?: return@transaction null
        if (current.status != ScreenShareStatus.LIVE) return@transaction current
        ScreenSharesTable.update({ ScreenSharesTable.shareId eq shareId }) {
            it[missingSnapshotCount] = current.missingSnapshotCount + 1
            it[updatedAtEpochMillis] = observedAtEpochMillis
        }
        findByShareIdInCurrentTransaction(shareId)
    }

    override fun requestStop(callId: String, stoppedAtEpochMillis: Long): ScreenShareTransition? = transaction {
        val current = ScreenSharesTable.selectAll()
            .where { ScreenSharesTable.callId eq callId }
            .orderBy(ScreenSharesTable.createdAtEpochMillis to SortOrder.DESC)
            .limit(1)
            .forUpdate()
            .singleOrNull()
            ?.toRecord()
            ?: return@transaction null
        if (current.status !in activeStatuses) return@transaction ScreenShareTransition(current, changed = false)
        transition(current, ScreenShareStatus.STOPPING, stoppedAtEpochMillis, null)
    }

    override fun markExpired(shareId: String, expiredAtEpochMillis: Long): ScreenShareTransition? = transaction {
        val current = findByShareIdForUpdate(shareId) ?: return@transaction null
        if (current.status !in activeStatuses) return@transaction ScreenShareTransition(current, changed = false)
        transition(current, ScreenShareStatus.EXPIRED, expiredAtEpochMillis, "Screen share authorization expired")
    }

    override fun markFailed(shareId: String, failedAtEpochMillis: Long, message: String): ScreenShareTransition? = transaction {
        val current = findByShareIdForUpdate(shareId) ?: return@transaction null
        if (current.status in terminalStatuses) return@transaction ScreenShareTransition(current, changed = false)
        transition(current, ScreenShareStatus.FAILED, failedAtEpochMillis, message)
    }

    override fun markStopped(
        shareId: String,
        stoppedAtEpochMillis: Long,
        message: String?,
    ): ScreenShareTransition? = transaction {
        val current = findByShareIdForUpdate(shareId) ?: return@transaction null
        if (current.status == ScreenShareStatus.STOPPED) return@transaction ScreenShareTransition(current, changed = false)
        if (current.status != ScreenShareStatus.LIVE && current.status != ScreenShareStatus.STOPPING) {
            return@transaction ScreenShareTransition(current, changed = false)
        }
        transition(current, ScreenShareStatus.STOPPED, stoppedAtEpochMillis, message)
    }

    override fun markProviderCleaned(shareId: String, cleanedAtEpochMillis: Long): ScreenShareRecord? = transaction {
        ScreenSharesTable.update({ ScreenSharesTable.shareId eq shareId }) {
            it[providerCleanedAtEpochMillis] = cleanedAtEpochMillis
            it[updatedAtEpochMillis] = cleanedAtEpochMillis
        }
        findByShareIdInCurrentTransaction(shareId)
    }

    override fun recordProviderError(
        shareId: String,
        observedAtEpochMillis: Long,
        message: String,
    ): ScreenShareRecord? = transaction {
        ScreenSharesTable.update({ ScreenSharesTable.shareId eq shareId }) {
            it[updatedAtEpochMillis] = observedAtEpochMillis
            it[lastError] = message.take(MAX_ERROR_LENGTH)
        }
        findByShareIdInCurrentTransaction(shareId)
    }

    private fun transition(
        current: ScreenShareRecord,
        target: ScreenShareStatus,
        atEpochMillis: Long,
        message: String?,
    ): ScreenShareTransition {
        ScreenSharesTable.update({ ScreenSharesTable.shareId eq current.shareId }) {
            it[status] = target.wireValue
            it[activeCallId] = null
            it[updatedAtEpochMillis] = atEpochMillis
            it[stoppedAtEpochMillis] = atEpochMillis
            it[missingSnapshotCount] = 0
            it[lastError] = message?.take(MAX_ERROR_LENGTH)
        }
        return ScreenShareTransition(
            record = checkNotNull(findByShareIdInCurrentTransaction(current.shareId)),
            changed = current.status != target,
        )
    }

    private fun findActiveByCallInCurrentTransaction(callId: String): ScreenShareRecord? =
        ScreenSharesTable.selectAll()
            .where { ScreenSharesTable.activeCallId eq callId }
            .singleOrNull()
            ?.toRecord()

    private fun findByShareIdForUpdate(shareId: String): ScreenShareRecord? =
        ScreenSharesTable.selectAll()
            .where { ScreenSharesTable.shareId eq shareId }
            .forUpdate()
            .singleOrNull()
            ?.toRecord()

    private fun findByShareIdInCurrentTransaction(shareId: String): ScreenShareRecord? =
        ScreenSharesTable.selectAll()
            .where { ScreenSharesTable.shareId eq shareId }
            .singleOrNull()
            ?.toRecord()

    private fun ResultRow.toRecord() = ScreenShareRecord(
        shareId = this[ScreenSharesTable.shareId],
        callId = this[ScreenSharesTable.callId],
        ownerUserId = this[ScreenSharesTable.ownerUserId],
        source = ScreenShareSource.entries.first { it.wireValue == this[ScreenSharesTable.sourceType] },
        mediaPath = this[ScreenSharesTable.mediaPath],
        status = ScreenShareStatus.entries.first { it.wireValue == this[ScreenSharesTable.status] },
        createdAtEpochMillis = this[ScreenSharesTable.createdAtEpochMillis],
        updatedAtEpochMillis = this[ScreenSharesTable.updatedAtEpochMillis],
        expiresAtEpochMillis = this[ScreenSharesTable.expiresAtEpochMillis],
        liveAtEpochMillis = this[ScreenSharesTable.liveAtEpochMillis],
        stoppedAtEpochMillis = this[ScreenSharesTable.stoppedAtEpochMillis],
        providerSourceType = this[ScreenSharesTable.providerSourceType],
        providerSourceId = this[ScreenSharesTable.providerSourceId],
        missingSnapshotCount = this[ScreenSharesTable.missingSnapshotCount],
        providerCleanedAtEpochMillis = this[ScreenSharesTable.providerCleanedAtEpochMillis],
        lastError = this[ScreenSharesTable.lastError],
    )

    private fun observedMillis(current: ScreenShareRecord, observedAtEpochMillis: Long): Long =
        maxOf(current.updatedAtEpochMillis, observedAtEpochMillis)

    private companion object {
        const val MAX_ERROR_LENGTH = 2_048
        val activeStatuses = setOf(ScreenShareStatus.AUTHORIZED, ScreenShareStatus.LIVE)
        val terminalStatuses = setOf(
            ScreenShareStatus.STOPPED,
            ScreenShareStatus.EXPIRED,
            ScreenShareStatus.FAILED,
        )
        val openStatusValues = listOf(
            ScreenShareStatus.AUTHORIZED.wireValue,
            ScreenShareStatus.LIVE.wireValue,
            ScreenShareStatus.STOPPING.wireValue,
        )
        val terminalCleanupStatusValues = listOf(
            ScreenShareStatus.STOPPED.wireValue,
            ScreenShareStatus.EXPIRED.wireValue,
            ScreenShareStatus.FAILED.wireValue,
        )
    }
}
