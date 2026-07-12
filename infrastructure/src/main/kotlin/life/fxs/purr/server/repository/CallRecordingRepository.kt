package life.fxs.purr.server.repository

import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingRecord
import life.fxs.purr.server.db.table.CallRecordingsTable
import life.fxs.purr.server.db.table.CallSessionsTable
import life.fxs.purr.server.model.RecordingStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class CallRecordingRepository : CallRecordingStore {
    override fun updateCurrent(callId: String, result: ProviderRecordingResult): Boolean = transaction {
        val call = CallSessionsTable.selectAll()
            .where { CallSessionsTable.callId eq callId }
            .singleOrNull()
            ?: return@transaction false

        val resultIsCurrent = result.recordingId == call[CallSessionsTable.recordingId] ||
            call[CallSessionsTable.recordingId] == null
        if (resultIsCurrent && result.updatedAtEpochMillis >= call[CallSessionsTable.updatedAtEpochMillis]) {
            CallSessionsTable.update({ CallSessionsTable.callId eq callId }) {
                it[recordingStatus] = result.status.wireValue
                it[recordingId] = result.recordingId
                it[recordingRecoveryAttempts] = 0
                it[recordingLastRecoveryAtEpochMillis] = null
                it[recordingErrorMessage] = result.errorMessage?.take(MAX_RECORDING_ERROR_LENGTH)
                it[updatedAtEpochMillis] = result.updatedAtEpochMillis
            }
        }
        result.recordingId?.let { recordingId ->
            upsertInCurrentTransaction(callId, recordingId, result)
        }
        true
    }

    override fun findByCallId(callId: String): List<RecordingRecord> = transaction {
        CallRecordingsTable.selectAll()
            .where { CallRecordingsTable.callId eq callId }
            .orderBy(CallRecordingsTable.createdAtEpochMillis to SortOrder.DESC)
            .map { it.toRecordingRecord() }
    }

    override fun findByRecordingId(recordingId: String): RecordingRecord? = transaction {
        CallRecordingsTable.selectAll()
            .where { CallRecordingsTable.recordingId eq recordingId }
            .singleOrNull()
            ?.toRecordingRecord()
    }

    fun findRetentionCandidates(
        updatedBeforeEpochMillis: Long,
        retryBeforeEpochMillis: Long,
        maxAttempts: Int,
        limit: Int,
    ): List<RecordingRecord> = transaction {
        CallRecordingsTable.selectAll()
            .where {
                (CallRecordingsTable.recordingStatus inList listOf(
                    RecordingStatus.STOPPED.wireValue,
                    RecordingStatus.FAILED.wireValue,
                )) and
                    CallRecordingsTable.objectKey.isNotNull() and
                    CallRecordingsTable.deletedAtEpochMillis.isNull() and
                    (CallRecordingsTable.updatedAtEpochMillis less updatedBeforeEpochMillis) and
                    (CallRecordingsTable.deletionAttempts less maxAttempts) and
                    (
                        CallRecordingsTable.lastDeletionAttemptAtEpochMillis.isNull() or
                            (CallRecordingsTable.lastDeletionAttemptAtEpochMillis less retryBeforeEpochMillis)
                        )
            }
            .orderBy(CallRecordingsTable.updatedAtEpochMillis to SortOrder.ASC)
            .limit(limit)
            .map { it.toRecordingRecord() }
    }

    fun claimDeletion(
        candidate: RecordingRecord,
        attemptedAtEpochMillis: Long,
        maxAttempts: Int,
    ): RecordingRecord? {
        val updated = transaction {
            val sameLastAttempt = candidate.lastDeletionAttemptAtEpochMillis?.let {
                CallRecordingsTable.lastDeletionAttemptAtEpochMillis eq it
            } ?: CallRecordingsTable.lastDeletionAttemptAtEpochMillis.isNull()
            CallRecordingsTable.update(
                where = {
                    (CallRecordingsTable.recordingId eq candidate.recordingId) and
                        (CallRecordingsTable.deletionAttempts eq candidate.deletionAttempts) and
                        (CallRecordingsTable.deletionAttempts less maxAttempts) and
                        CallRecordingsTable.deletedAtEpochMillis.isNull() and
                        sameLastAttempt
                },
            ) {
                it[deletionAttempts] = candidate.deletionAttempts + 1
                it[lastDeletionAttemptAtEpochMillis] = attemptedAtEpochMillis
                it[deletionErrorMessage] = null
            }
        }
        return if (updated == 1) findByRecordingId(candidate.recordingId) else null
    }

    fun markDeleted(recordingId: String, deletedAtEpochMillis: Long) {
        transaction {
            CallRecordingsTable.update({ CallRecordingsTable.recordingId eq recordingId }) {
                it[recordingStatus] = RecordingStatus.DELETED.wireValue
                it[objectKey] = null
                it[location] = null
                it[CallRecordingsTable.deletedAtEpochMillis] = deletedAtEpochMillis
                it[deletionErrorMessage] = null
                it[updatedAtEpochMillis] = deletedAtEpochMillis
            }
        }
    }

    fun recordDeletionFailure(recordingId: String, message: String) {
        transaction {
            CallRecordingsTable.update({ CallRecordingsTable.recordingId eq recordingId }) {
                it[deletionErrorMessage] = message.take(MAX_RECORDING_ERROR_LENGTH)
            }
        }
    }

    private fun upsertInCurrentTransaction(
        callId: String,
        recordingId: String,
        result: ProviderRecordingResult,
    ) {
        val safeErrorMessage = result.errorMessage?.take(MAX_RECORDING_ERROR_LENGTH)
        CallRecordingsTable.insertIgnore {
            it[CallRecordingsTable.recordingId] = recordingId
            it[CallRecordingsTable.callId] = callId
            it[recordingStatus] = result.status.wireValue
            it[objectKey] = result.objectKey
            it[location] = result.location
            it[startedAtEpochMillis] = result.startedAtEpochMillis
            it[endedAtEpochMillis] = result.endedAtEpochMillis
            it[durationMillis] = result.durationMillis
            it[sizeBytes] = result.sizeBytes
            it[errorCode] = result.errorCode
            it[errorMessage] = safeErrorMessage
            it[createdAtEpochMillis] = result.updatedAtEpochMillis
            it[updatedAtEpochMillis] = result.updatedAtEpochMillis
            it[deletedAtEpochMillis] = null
            it[deletionAttempts] = 0
            it[lastDeletionAttemptAtEpochMillis] = null
            it[deletionErrorMessage] = null
        }

        val existing = CallRecordingsTable.selectAll()
            .where { CallRecordingsTable.recordingId eq recordingId }
            .single()
        if (result.updatedAtEpochMillis < existing[CallRecordingsTable.updatedAtEpochMillis]) return

        CallRecordingsTable.update({ CallRecordingsTable.recordingId eq recordingId }) {
            it[recordingStatus] = result.status.wireValue
            it[objectKey] = result.objectKey ?: existing[CallRecordingsTable.objectKey]
            it[location] = result.location ?: existing[CallRecordingsTable.location]
            it[startedAtEpochMillis] = result.startedAtEpochMillis
                ?: existing[CallRecordingsTable.startedAtEpochMillis]
            it[endedAtEpochMillis] = result.endedAtEpochMillis
                ?: existing[CallRecordingsTable.endedAtEpochMillis]
            it[durationMillis] = result.durationMillis ?: existing[CallRecordingsTable.durationMillis]
            it[sizeBytes] = result.sizeBytes ?: existing[CallRecordingsTable.sizeBytes]
            it[errorCode] = result.errorCode
            it[errorMessage] = safeErrorMessage
            it[updatedAtEpochMillis] = result.updatedAtEpochMillis
        }
    }

    private fun ResultRow.toRecordingRecord() = RecordingRecord(
        recordingId = this[CallRecordingsTable.recordingId],
        callId = this[CallRecordingsTable.callId],
        status = RecordingStatus.entries.first { it.wireValue == this[CallRecordingsTable.recordingStatus] },
        objectKey = this[CallRecordingsTable.objectKey],
        location = this[CallRecordingsTable.location],
        startedAtEpochMillis = this[CallRecordingsTable.startedAtEpochMillis],
        endedAtEpochMillis = this[CallRecordingsTable.endedAtEpochMillis],
        durationMillis = this[CallRecordingsTable.durationMillis],
        sizeBytes = this[CallRecordingsTable.sizeBytes],
        errorCode = this[CallRecordingsTable.errorCode],
        errorMessage = this[CallRecordingsTable.errorMessage],
        createdAtEpochMillis = this[CallRecordingsTable.createdAtEpochMillis],
        updatedAtEpochMillis = this[CallRecordingsTable.updatedAtEpochMillis],
        deletedAtEpochMillis = this[CallRecordingsTable.deletedAtEpochMillis],
        deletionAttempts = this[CallRecordingsTable.deletionAttempts],
        lastDeletionAttemptAtEpochMillis = this[CallRecordingsTable.lastDeletionAttemptAtEpochMillis],
        deletionErrorMessage = this[CallRecordingsTable.deletionErrorMessage],
    )
}

private const val MAX_RECORDING_ERROR_LENGTH = 2_048
