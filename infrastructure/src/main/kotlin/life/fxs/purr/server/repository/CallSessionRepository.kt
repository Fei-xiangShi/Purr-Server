package life.fxs.purr.server.repository

import life.fxs.purr.server.application.port.ActiveCallResolution
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.CallRoomReconciliationStore
import life.fxs.purr.server.application.port.EndCallResolution
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.db.table.CallSessionsTable
import life.fxs.purr.server.db.table.PairBondsTable
import life.fxs.purr.server.model.CallDurationPolicy
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class CallSessionRepository : CallSessionStore, CallRoomReconciliationStore {
    override fun find(callId: String): CallRecord? = transaction {
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.callId eq callId }
            .singleOrNull()
            ?.toCallRecord()
    }

    override fun findByRecordingId(recordingId: String): CallRecord? = transaction {
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.recordingId eq recordingId }
            .singleOrNull()
            ?.toCallRecord()
    }

    override fun findByRoomName(roomName: String): CallRecord? = transaction {
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.roomName eq roomName }
            .singleOrNull()
            ?.toCallRecord()
    }

    override fun findActiveByPair(pairId: String): CallRecord? = transaction {
        findActiveByPairInCurrentTransaction(pairId)
    }

    override fun findOrCreateActive(
        pairId: String,
        newCall: () -> CallRecord,
    ): ActiveCallResolution = transaction {
        PairBondsTable.selectAll()
            .where { PairBondsTable.pairId eq pairId }
            .forUpdate()
            .single()

        val existing = findActiveByPairInCurrentTransaction(pairId)
        if (existing != null) {
            ActiveCallResolution(existing, created = false)
        } else {
            val created = newCall().also(::insertInCurrentTransaction)
            ActiveCallResolution(created, created = true)
        }
    }

    fun upsert(call: CallRecord): CallRecord {
        transaction {
            insertInCurrentTransaction(call)
        }
        return call
    }

    override fun activateIfWaiting(callId: String, connectedAtEpochMillis: Long): CallRecord? {
        transaction {
            CallSessionsTable.update(
                where = {
                    (CallSessionsTable.callId eq callId) and
                        (CallSessionsTable.callState eq CallState.WAITING.wireValue) and
                        CallSessionsTable.connectedAtEpochMillis.isNull()
                },
            ) {
                it[callState] = CallState.ACTIVE.wireValue
                it[CallSessionsTable.connectedAtEpochMillis] = connectedAtEpochMillis
                it[roomEmptySinceEpochMillis] = null
                it[updatedAtEpochMillis] = connectedAtEpochMillis
            }
        }
        return find(callId)
    }

    override fun endIfWaiting(callId: String, endedAtEpochMillis: Long): EndCallResolution? =
        endIfStateMatches(callId, endedAtEpochMillis, listOf(CallState.WAITING.wireValue))

    override fun endIfOpen(callId: String, endedAtEpochMillis: Long): EndCallResolution? =
        endIfStateMatches(callId, endedAtEpochMillis, openCallStates)

    private fun endIfStateMatches(
        callId: String,
        endedAtEpochMillis: Long,
        expectedStates: List<String>,
    ): EndCallResolution? {
        val updatedRows = transaction {
            val candidate = CallSessionsTable.selectAll()
                .where {
                    (CallSessionsTable.callId eq callId) and
                        (CallSessionsTable.callState inList expectedStates)
                }
                .forUpdate()
                .singleOrNull()
                ?: return@transaction 0
            val connectedAtEpochMillis = candidate[CallSessionsTable.connectedAtEpochMillis]
            CallSessionsTable.update(
                where = {
                    (CallSessionsTable.callId eq callId) and
                        (CallSessionsTable.callState inList expectedStates)
                },
            ) {
                it[callState] = CallState.ENDED.wireValue
                it[activePairId] = null
                it[CallSessionsTable.endedAtEpochMillis] = endedAtEpochMillis
                it[durationMillis] = CallDurationPolicy.completedDurationMillis(
                    connectedAtEpochMillis = connectedAtEpochMillis,
                    endedAtEpochMillis = endedAtEpochMillis,
                )
                it[roomEmptySinceEpochMillis] = null
                it[updatedAtEpochMillis] = endedAtEpochMillis
            }
        }
        val call = find(callId) ?: return null
        return EndCallResolution(call, endedNow = updatedRows == 1)
    }

    override fun claimRecordingStart(callId: String, updatedAtEpochMillis: Long): CallRecord? {
        return claimRecordingStartInternal(
            callId = callId,
            updatedAtEpochMillis = updatedAtEpochMillis,
            latestEligibleConnectedAtEpochMillis = null,
        )
    }

    override fun claimRecordingStartAfterMinimumDuration(
        callId: String,
        updatedAtEpochMillis: Long,
        minimumConnectedDurationMillis: Long,
    ): CallRecord? = claimRecordingStartInternal(
        callId = callId,
        updatedAtEpochMillis = updatedAtEpochMillis,
        latestEligibleConnectedAtEpochMillis = updatedAtEpochMillis - minimumConnectedDurationMillis,
    )

    private fun claimRecordingStartInternal(
        callId: String,
        updatedAtEpochMillis: Long,
        latestEligibleConnectedAtEpochMillis: Long?,
    ): CallRecord? {
        val updatedRows = transaction {
            CallSessionsTable.update(
                where = {
                    val eligibleConnection = latestEligibleConnectedAtEpochMillis?.let {
                        CallSessionsTable.connectedAtEpochMillis.isNotNull() and
                            (CallSessionsTable.connectedAtEpochMillis lessEq it)
                    } ?: org.jetbrains.exposed.sql.Op.TRUE
                    (CallSessionsTable.callId eq callId) and
                        (CallSessionsTable.callState eq CallState.ACTIVE.wireValue) and
                        (CallSessionsTable.recordingStatus inList startableRecordingStatuses) and
                        eligibleConnection
                },
            ) {
                it[recordingStatus] = RecordingStatus.STARTING.wireValue
                it[recordingId] = null
                it[recordingProviderUpdatedAtEpochMillis] = null
                it[recordingRecoveryAttempts] = 0
                it[recordingLastRecoveryAtEpochMillis] = null
                it[recordingErrorMessage] = null
                it[CallSessionsTable.updatedAtEpochMillis] = updatedAtEpochMillis
            }
        }
        if (updatedRows == 0) {
            return null
        }
        return find(callId)
    }

    override fun claimRecordingStop(
        callId: String,
        recordingId: String?,
        updatedAtEpochMillis: Long,
    ): CallRecord? {
        val updatedRows = transaction {
            CallSessionsTable.update(
                where = {
                    (CallSessionsTable.callId eq callId) and
                        (CallSessionsTable.recordingStatus inList listOf(
                            RecordingStatus.STARTING.wireValue,
                            RecordingStatus.RECORDING.wireValue,
                        )) and
                        (recordingId?.let { CallSessionsTable.recordingId eq it }
                            ?: CallSessionsTable.recordingId.isNull())
                },
            ) {
                it[recordingStatus] = RecordingStatus.STOPPING.wireValue
                // A START command may not have received its provider id yet;
                // the STOP command remains nullable and resolves it later.
                if (recordingId != null) it[CallSessionsTable.recordingId] = recordingId
            }
        }
        return if (updatedRows == 1) find(callId) else null
    }

    override fun findEndedByPairId(
        pairId: String,
        limit: Int,
        cursor: CallHistoryCursor?,
    ): List<CallRecord> = transaction {
        val endedCalls =
            (CallSessionsTable.pairId eq pairId) and
                (CallSessionsTable.callState eq CallState.ENDED.wireValue) and
                CallSessionsTable.endedAtEpochMillis.isNotNull() and
                CallSessionsTable.connectedAtEpochMillis.isNotNull() and
                (CallSessionsTable.durationMillis greaterEq CallDurationPolicy.MINIMUM_HISTORY_DURATION_MILLIS)
        val condition = cursor?.let {
            endedCalls and (
                (CallSessionsTable.startedAtEpochMillis less it.startedAtEpochMillis) or
                    (
                        (CallSessionsTable.startedAtEpochMillis eq it.startedAtEpochMillis) and
                            (CallSessionsTable.callId less it.callId)
                        )
                )
        } ?: endedCalls
        CallSessionsTable.selectAll()
            .where { condition }
            .orderBy(
                CallSessionsTable.startedAtEpochMillis to SortOrder.DESC,
                CallSessionsTable.callId to SortOrder.DESC,
            )
            .limit(limit)
            .map { it.toCallRecord() }
    }

    override fun findEndedByPairIdBetween(
        pairId: String,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        limit: Int,
        cursor: CallHistoryCursor?,
    ): List<CallRecord> = transaction {
        val inRange =
            (CallSessionsTable.pairId eq pairId) and
                (CallSessionsTable.callState eq CallState.ENDED.wireValue) and
                CallSessionsTable.endedAtEpochMillis.isNotNull() and
                CallSessionsTable.connectedAtEpochMillis.isNotNull() and
                (CallSessionsTable.durationMillis greaterEq CallDurationPolicy.MINIMUM_HISTORY_DURATION_MILLIS) and
                (CallSessionsTable.startedAtEpochMillis greaterEq fromEpochMillis) and
                (CallSessionsTable.startedAtEpochMillis less toEpochMillis)
        val condition = cursor?.let {
            inRange and (
                (CallSessionsTable.startedAtEpochMillis less it.startedAtEpochMillis) or
                    (
                        (CallSessionsTable.startedAtEpochMillis eq it.startedAtEpochMillis) and
                            (CallSessionsTable.callId less it.callId)
                        )
                )
        } ?: inRange
        CallSessionsTable.selectAll()
            .where { condition }
            .orderBy(
                CallSessionsTable.startedAtEpochMillis to SortOrder.DESC,
                CallSessionsTable.callId to SortOrder.DESC,
            )
            .limit(limit)
            .map { it.toCallRecord() }
    }

    override fun findOpenCalls(limit: Int): List<CallRecord> = transaction {
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.callState inList openCallStates }
            .orderBy(CallSessionsTable.startedAtEpochMillis to SortOrder.ASC)
            .limit(limit)
            .map { it.toCallRecord() }
    }

    override fun observeRoomEmpty(
        callId: String,
        observedAtEpochMillis: Long,
    ): CallRecord? {
        transaction {
            CallSessionsTable.update(
                where = {
                    (CallSessionsTable.callId eq callId) and
                        (CallSessionsTable.callState eq CallState.ACTIVE.wireValue) and
                        CallSessionsTable.roomEmptySinceEpochMillis.isNull()
                },
            ) {
                it[roomEmptySinceEpochMillis] = observedAtEpochMillis
            }
        }
        return find(callId)?.takeIf { it.state == CallState.ACTIVE }
    }

    override fun clearRoomEmptyObservation(callId: String): Boolean = transaction {
        CallSessionsTable.update(
            where = {
                (CallSessionsTable.callId eq callId) and
                    (CallSessionsTable.callState eq CallState.ACTIVE.wireValue) and
                    CallSessionsTable.roomEmptySinceEpochMillis.isNotNull()
            },
        ) {
            it[roomEmptySinceEpochMillis] = null
        } == 1
    }

    fun updateRecording(
        callId: String,
        recordingStatus: RecordingStatus,
        recordingId: String?,
        updatedAtEpochMillis: Long,
    ): CallRecord? {
        transaction {
            CallSessionsTable.update({ CallSessionsTable.callId eq callId }) {
                it[CallSessionsTable.recordingStatus] = recordingStatus.wireValue
                it[CallSessionsTable.recordingId] = recordingId
                it[recordingRecoveryAttempts] = 0
                it[recordingLastRecoveryAtEpochMillis] = null
                it[recordingErrorMessage] = null
                it[recordingProviderUpdatedAtEpochMillis] = updatedAtEpochMillis
            }
        }
        return find(callId)
    }

    fun findRecordingRecoveryCandidates(
        staleBeforeEpochMillis: Long,
        retryBeforeEpochMillis: Long,
        maxAttempts: Int,
    ): List<CallRecord> = transaction {
        val neverRecovered = CallSessionsTable.selectAll()
            .where {
                (CallSessionsTable.recordingStatus inList recoverableRecordingStatuses) and
                    (CallSessionsTable.updatedAtEpochMillis lessEq staleBeforeEpochMillis) and
                    (CallSessionsTable.recordingRecoveryAttempts less maxAttempts) and
                    CallSessionsTable.recordingLastRecoveryAtEpochMillis.isNull()
            }
            .map { it.toCallRecord() }
        val retryDue = CallSessionsTable.selectAll()
            .where {
                (CallSessionsTable.recordingStatus inList recoverableRecordingStatuses) and
                    (CallSessionsTable.updatedAtEpochMillis lessEq staleBeforeEpochMillis) and
                    (CallSessionsTable.recordingRecoveryAttempts less maxAttempts) and
                    (CallSessionsTable.recordingLastRecoveryAtEpochMillis lessEq retryBeforeEpochMillis)
            }
            .map { it.toCallRecord() }
        neverRecovered + retryDue
    }

    fun claimRecordingRecovery(
        candidate: CallRecord,
        claimedAtEpochMillis: Long,
        maxAttempts: Int,
    ): CallRecord? {
        val updatedRows = transaction {
            CallSessionsTable.update(
                where = {
                    val sameLastRecovery = candidate.recordingLastRecoveryAtEpochMillis?.let {
                        CallSessionsTable.recordingLastRecoveryAtEpochMillis eq it
                    } ?: CallSessionsTable.recordingLastRecoveryAtEpochMillis.isNull()
                    (CallSessionsTable.callId eq candidate.callId) and
                        (CallSessionsTable.recordingStatus eq candidate.recordingStatus.wireValue) and
                        (CallSessionsTable.recordingRecoveryAttempts eq candidate.recordingRecoveryAttempts) and
                        (CallSessionsTable.recordingRecoveryAttempts less maxAttempts) and
                        sameLastRecovery
                },
            ) {
                it[recordingRecoveryAttempts] = candidate.recordingRecoveryAttempts + 1
                it[recordingLastRecoveryAtEpochMillis] = claimedAtEpochMillis
            }
        }
        return if (updatedRows == 1) find(candidate.callId) else null
    }

    fun recordRecoveryFailure(
        callId: String,
        message: String,
        failedAtEpochMillis: Long,
        terminal: Boolean,
    ) {
        transaction {
            CallSessionsTable.update({ CallSessionsTable.callId eq callId }) {
                it[recordingErrorMessage] = message.take(MAX_RECORDING_ERROR_LENGTH)
                if (terminal) {
                    it[recordingStatus] = RecordingStatus.FAILED.wireValue
                }
            }
        }
    }

    private fun ResultRow.toCallRecord(): CallRecord = CallRecord(
        callId = this[CallSessionsTable.callId],
        pairId = this[CallSessionsTable.pairId],
        roomName = this[CallSessionsTable.roomName],
        createdByUserId = this[CallSessionsTable.createdByUserId],
        startedAtEpochMillis = this[CallSessionsTable.startedAtEpochMillis],
        updatedAtEpochMillis = this[CallSessionsTable.updatedAtEpochMillis],
        recordingProviderUpdatedAtEpochMillis = this[CallSessionsTable.recordingProviderUpdatedAtEpochMillis],
        state = CallState.entries.first { it.wireValue == this[CallSessionsTable.callState] },
        recordingStatus = RecordingStatus.entries.first { it.wireValue == this[CallSessionsTable.recordingStatus] },
        recordingId = this[CallSessionsTable.recordingId],
        recordingRecoveryAttempts = this[CallSessionsTable.recordingRecoveryAttempts],
        recordingLastRecoveryAtEpochMillis = this[CallSessionsTable.recordingLastRecoveryAtEpochMillis],
        recordingErrorMessage = this[CallSessionsTable.recordingErrorMessage],
        endedAtEpochMillis = this[CallSessionsTable.endedAtEpochMillis],
        connectedAtEpochMillis = this[CallSessionsTable.connectedAtEpochMillis],
        durationMillis = this[CallSessionsTable.durationMillis],
        roomEmptySinceEpochMillis = this[CallSessionsTable.roomEmptySinceEpochMillis],
    )

    private fun findActiveByPairInCurrentTransaction(pairId: String): CallRecord? =
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.activePairId eq pairId }
            .singleOrNull()
            ?.toCallRecord()

    private fun insertInCurrentTransaction(call: CallRecord) {
        CallSessionsTable.insert {
            it[callId] = call.callId
            it[pairId] = call.pairId
            it[activePairId] = call.pairId.takeIf { call.state in openCallStatesAsEnums }
            it[roomName] = call.roomName
            it[createdByUserId] = call.createdByUserId
            it[startedAtEpochMillis] = call.startedAtEpochMillis
            it[updatedAtEpochMillis] = call.updatedAtEpochMillis
            it[recordingProviderUpdatedAtEpochMillis] = call.recordingProviderUpdatedAtEpochMillis
            it[endedAtEpochMillis] = call.endedAtEpochMillis
            it[connectedAtEpochMillis] = call.connectedAtEpochMillis
            it[durationMillis] = call.durationMillis ?: CallDurationPolicy.completedDurationMillis(
                connectedAtEpochMillis = call.connectedAtEpochMillis,
                endedAtEpochMillis = call.endedAtEpochMillis,
            )
            it[roomEmptySinceEpochMillis] = call.roomEmptySinceEpochMillis
            it[callState] = call.state.wireValue
            it[recordingStatus] = call.recordingStatus.wireValue
            it[recordingId] = call.recordingId
            it[recordingRecoveryAttempts] = call.recordingRecoveryAttempts
            it[recordingLastRecoveryAtEpochMillis] = call.recordingLastRecoveryAtEpochMillis
            it[recordingErrorMessage] = call.recordingErrorMessage
        }
    }

    private companion object {
        val startableRecordingStatuses = listOf(
            RecordingStatus.IDLE.wireValue,
            RecordingStatus.STOPPED.wireValue,
            RecordingStatus.FAILED.wireValue,
        )
        val recoverableRecordingStatuses = listOf(
            RecordingStatus.STARTING.wireValue,
            RecordingStatus.STOPPING.wireValue,
        )
        val openCallStates = listOf(CallState.WAITING.wireValue, CallState.ACTIVE.wireValue)
        val openCallStatesAsEnums = setOf(CallState.WAITING, CallState.ACTIVE)
        const val MAX_RECORDING_ERROR_LENGTH = 2_048
    }
}
