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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
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

        val currentRecordingId = call[CallSessionsTable.recordingId]
        val resultIsCurrent = result.recordingId == currentRecordingId || currentRecordingId == null
        val currentRecordingClock = call[CallSessionsTable.recordingProviderUpdatedAtEpochMillis]
            ?: Long.MIN_VALUE
        if (resultIsCurrent && result.updatedAtEpochMillis >= currentRecordingClock) {
            CallSessionsTable.update({ CallSessionsTable.callId eq callId }) {
                it[recordingStatus] = result.status.wireValue
                it[recordingId] = result.recordingId
                it[recordingRecoveryAttempts] = 0
                it[recordingLastRecoveryAtEpochMillis] = null
                it[recordingErrorMessage] = result.errorMessage?.take(MAX_RECORDING_ERROR_LENGTH)
                it[recordingProviderUpdatedAtEpochMillis] = result.updatedAtEpochMillis
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

    fun claimNextDriveUpload(
        workerId: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): RecordingRecord? = transaction {
        val candidates = CallRecordingsTable.selectAll()
            .where {
                (CallRecordingsTable.recordingStatus eq RecordingStatus.STOPPED.wireValue) and
                    CallRecordingsTable.objectKey.isNotNull() and
                    CallRecordingsTable.deletedAtEpochMillis.isNull() and
                    CallRecordingsTable.driveUploadedAtEpochMillis.isNull() and
                    CallRecordingsTable.driveUploadAvailableAtEpochMillis.isNotNull() and
                    (CallRecordingsTable.driveUploadAvailableAtEpochMillis lessEq nowEpochMillis) and
                    (
                        CallRecordingsTable.driveUploadLeaseUntilEpochMillis.isNull() or
                            (CallRecordingsTable.driveUploadLeaseUntilEpochMillis less nowEpochMillis)
                        )
            }
            .orderBy(CallRecordingsTable.createdAtEpochMillis to SortOrder.ASC)
            .limit(CLAIM_CANDIDATE_WINDOW)
            .map { it[CallRecordingsTable.recordingId] }

        candidates.firstNotNullOfOrNull { recordingId ->
            val claimed = CallRecordingsTable.update({
                (CallRecordingsTable.recordingId eq recordingId) and
                    (CallRecordingsTable.recordingStatus eq RecordingStatus.STOPPED.wireValue) and
                    CallRecordingsTable.objectKey.isNotNull() and
                    CallRecordingsTable.deletedAtEpochMillis.isNull() and
                    CallRecordingsTable.driveUploadedAtEpochMillis.isNull() and
                    (CallRecordingsTable.driveUploadAvailableAtEpochMillis lessEq nowEpochMillis) and
                    (
                        CallRecordingsTable.driveUploadLeaseUntilEpochMillis.isNull() or
                            (CallRecordingsTable.driveUploadLeaseUntilEpochMillis less nowEpochMillis)
                        )
            }) {
                it[driveUploadAttempts] = driveUploadAttempts + 1
                it[driveUploadLeaseOwner] = workerId
                it[driveUploadLeaseUntilEpochMillis] = leaseUntilEpochMillis
                it[driveUploadErrorMessage] = null
            } == 1
            if (!claimed) return@firstNotNullOfOrNull null
            CallRecordingsTable.selectAll()
                .where { CallRecordingsTable.recordingId eq recordingId }
                .single()
                .toRecordingRecord()
        }
    }

    fun markDriveUploaded(
        recordingId: String,
        workerId: String,
        driveFileId: String,
        uploadedAtEpochMillis: Long,
    ): Boolean = transaction {
        CallRecordingsTable.update({
            (CallRecordingsTable.recordingId eq recordingId) and
                (CallRecordingsTable.driveUploadLeaseOwner eq workerId) and
                CallRecordingsTable.driveUploadedAtEpochMillis.isNull()
        }) {
            it[CallRecordingsTable.driveFileId] = driveFileId
            it[driveUploadedAtEpochMillis] = uploadedAtEpochMillis
            it[driveUploadAvailableAtEpochMillis] = null
            it[driveUploadLeaseOwner] = null
            it[driveUploadLeaseUntilEpochMillis] = null
            it[driveUploadErrorMessage] = null
        } == 1
    }

    fun recordDriveUploadFailure(
        recordingId: String,
        workerId: String,
        availableAtEpochMillis: Long,
        message: String,
    ): Boolean = transaction {
        CallRecordingsTable.update({
            (CallRecordingsTable.recordingId eq recordingId) and
                (CallRecordingsTable.driveUploadLeaseOwner eq workerId) and
                CallRecordingsTable.driveUploadedAtEpochMillis.isNull()
        }) {
            it[driveUploadAvailableAtEpochMillis] = availableAtEpochMillis
            it[driveUploadLeaseOwner] = null
            it[driveUploadLeaseUntilEpochMillis] = null
            it[driveUploadErrorMessage] = message.take(MAX_RECORDING_ERROR_LENGTH)
        } == 1
    }

    fun findRetentionCandidates(
        endedBeforeEpochMillis: Long,
        nowEpochMillis: Long,
        limit: Int,
    ): List<RecordingRecord> = transaction {
        CallRecordingsTable.selectAll()
            .where {
                (CallRecordingsTable.recordingStatus eq RecordingStatus.STOPPED.wireValue) and
                    CallRecordingsTable.objectKey.isNotNull() and
                    CallRecordingsTable.deletedAtEpochMillis.isNull() and
                    CallRecordingsTable.driveFileId.isNotNull() and
                    CallRecordingsTable.driveUploadedAtEpochMillis.isNotNull() and
                    (
                        CallRecordingsTable.lastDeletionAttemptAtEpochMillis.isNull() or
                            (CallRecordingsTable.lastDeletionAttemptAtEpochMillis less nowEpochMillis)
                        ) and
                    (
                        (
                            CallRecordingsTable.endedAtEpochMillis.isNotNull() and
                                (CallRecordingsTable.endedAtEpochMillis less endedBeforeEpochMillis)
                            ) or
                            (
                                CallRecordingsTable.endedAtEpochMillis.isNull() and
                                    (CallRecordingsTable.updatedAtEpochMillis less endedBeforeEpochMillis)
                                )
                        ) and
                    (
                        CallRecordingsTable.deletionLeaseUntilEpochMillis.isNull() or
                            (CallRecordingsTable.deletionLeaseUntilEpochMillis less nowEpochMillis)
                        )
            }
            .orderBy(CallRecordingsTable.endedAtEpochMillis to SortOrder.ASC)
            .limit(limit)
            .map { it.toRecordingRecord() }
    }

    fun claimDeletion(
        candidate: RecordingRecord,
        workerId: String,
        attemptedAtEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): RecordingRecord? {
        val updated = transaction {
            val sameLastAttempt = candidate.lastDeletionAttemptAtEpochMillis?.let {
                CallRecordingsTable.lastDeletionAttemptAtEpochMillis eq it
            } ?: CallRecordingsTable.lastDeletionAttemptAtEpochMillis.isNull()
            CallRecordingsTable.update(
                where = {
                        (CallRecordingsTable.recordingId eq candidate.recordingId) and
                        (CallRecordingsTable.recordingStatus eq RecordingStatus.STOPPED.wireValue) and
                        CallRecordingsTable.objectKey.isNotNull() and
                        (CallRecordingsTable.deletionAttempts eq candidate.deletionAttempts) and
                        CallRecordingsTable.deletedAtEpochMillis.isNull() and
                        CallRecordingsTable.driveFileId.isNotNull() and
                        CallRecordingsTable.driveUploadedAtEpochMillis.isNotNull() and
                        (
                            CallRecordingsTable.deletionLeaseUntilEpochMillis.isNull() or
                                (CallRecordingsTable.deletionLeaseUntilEpochMillis less attemptedAtEpochMillis)
                            ) and
                        sameLastAttempt
                },
            ) {
                it[deletionAttempts] = candidate.deletionAttempts + 1
                it[lastDeletionAttemptAtEpochMillis] = attemptedAtEpochMillis
                it[deletionErrorMessage] = null
                it[deletionLeaseOwner] = workerId
                it[deletionLeaseUntilEpochMillis] = leaseUntilEpochMillis
            }
        }
        return if (updated == 1) findByRecordingId(candidate.recordingId) else null
    }

    fun markDeleted(recordingId: String, workerId: String, deletedAtEpochMillis: Long): Boolean = transaction {
            CallRecordingsTable.update({
                (CallRecordingsTable.recordingId eq recordingId) and
                    (CallRecordingsTable.deletionLeaseOwner eq workerId)
            }) {
                it[recordingStatus] = RecordingStatus.DELETED.wireValue
                it[objectKey] = null
                it[location] = null
                it[CallRecordingsTable.deletedAtEpochMillis] = deletedAtEpochMillis
                it[deletionErrorMessage] = null
                it[deletionLeaseOwner] = null
                it[deletionLeaseUntilEpochMillis] = null
                it[updatedAtEpochMillis] = deletedAtEpochMillis
            } == 1
    }

    fun recordDeletionFailure(recordingId: String, workerId: String, message: String): Boolean = transaction {
            CallRecordingsTable.update({
                (CallRecordingsTable.recordingId eq recordingId) and
                    (CallRecordingsTable.deletionLeaseOwner eq workerId)
            }) {
                it[deletionErrorMessage] = message.take(MAX_RECORDING_ERROR_LENGTH)
                it[deletionLeaseOwner] = null
                it[deletionLeaseUntilEpochMillis] = null
            } == 1
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
            it[deletionLeaseOwner] = null
            it[deletionLeaseUntilEpochMillis] = null
            it[driveFileId] = null
            it[driveUploadedAtEpochMillis] = null
            it[driveUploadAttempts] = 0
            it[driveUploadAvailableAtEpochMillis] = result.updatedAtEpochMillis
                .takeIf { result.status == RecordingStatus.STOPPED && result.objectKey != null }
            it[driveUploadLeaseOwner] = null
            it[driveUploadLeaseUntilEpochMillis] = null
            it[driveUploadErrorMessage] = null
        }

        val existing = CallRecordingsTable.selectAll()
            .where { CallRecordingsTable.recordingId eq recordingId }
            .single()
        if (result.updatedAtEpochMillis < existing[CallRecordingsTable.updatedAtEpochMillis]) return

        val effectiveObjectKey = result.objectKey ?: existing[CallRecordingsTable.objectKey]
        val shouldQueueDriveUpload = result.status == RecordingStatus.STOPPED &&
            effectiveObjectKey != null &&
            existing[CallRecordingsTable.driveUploadedAtEpochMillis] == null
        CallRecordingsTable.update({ CallRecordingsTable.recordingId eq recordingId }) {
            it[recordingStatus] = result.status.wireValue
            it[objectKey] = effectiveObjectKey
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
            if (shouldQueueDriveUpload && existing[CallRecordingsTable.driveUploadAvailableAtEpochMillis] == null) {
                it[driveUploadAvailableAtEpochMillis] = result.updatedAtEpochMillis
            }
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
        deletionLeaseOwner = this[CallRecordingsTable.deletionLeaseOwner],
        deletionLeaseUntilEpochMillis = this[CallRecordingsTable.deletionLeaseUntilEpochMillis],
        driveFileId = this[CallRecordingsTable.driveFileId],
        driveUploadedAtEpochMillis = this[CallRecordingsTable.driveUploadedAtEpochMillis],
        driveUploadAttempts = this[CallRecordingsTable.driveUploadAttempts],
        driveUploadAvailableAtEpochMillis = this[CallRecordingsTable.driveUploadAvailableAtEpochMillis],
        driveUploadLeaseOwner = this[CallRecordingsTable.driveUploadLeaseOwner],
        driveUploadLeaseUntilEpochMillis = this[CallRecordingsTable.driveUploadLeaseUntilEpochMillis],
        driveUploadErrorMessage = this[CallRecordingsTable.driveUploadErrorMessage],
    )
}

private const val MAX_RECORDING_ERROR_LENGTH = 2_048
private const val CLAIM_CANDIDATE_WINDOW = 16
