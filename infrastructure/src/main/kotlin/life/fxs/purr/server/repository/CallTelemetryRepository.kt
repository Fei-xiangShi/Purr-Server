package life.fxs.purr.server.repository

import life.fxs.purr.server.application.port.CallTelemetrySample
import life.fxs.purr.server.application.port.CallTelemetryStore
import life.fxs.purr.server.db.table.CallTelemetrySamplesTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class CallTelemetryRepository : CallTelemetryStore {
    override fun append(sample: CallTelemetrySample) {
        transaction {
            CallTelemetrySamplesTable.insertIgnore {
                it[callId] = sample.callId
                it[userId] = sample.userId
                it[sampledAtEpochMillis] = sample.sampledAtEpochMillis
                it[roundTripTimeMs] = sample.roundTripTimeMs
                it[jitterMs] = sample.jitterMs
                it[uplinkPacketLossPercent] = sample.uplinkPacketLossPercent
                it[downlinkPacketLossPercent] = sample.downlinkPacketLossPercent
                it[uplinkBitrateKbps] = sample.uplinkBitrateKbps
                it[downlinkBitrateKbps] = sample.downlinkBitrateKbps
                it[networkTransport] = sample.networkTransport
                it[sendCodec] = sample.sendCodec
                it[receiveCodec] = sample.receiveCodec
                it[networkValidated] = sample.networkValidated
                it[networkMetered] = sample.networkMetered
            }
        }
    }

    override fun findByCallId(callId: String): List<CallTelemetrySample> = transaction {
        CallTelemetrySamplesTable.selectAll()
            .where { CallTelemetrySamplesTable.callId eq callId }
            .orderBy(CallTelemetrySamplesTable.sampledAtEpochMillis to SortOrder.ASC)
            .map(ResultRow::toTelemetrySample)
    }
}

private fun ResultRow.toTelemetrySample() = CallTelemetrySample(
    callId = this[CallTelemetrySamplesTable.callId],
    userId = this[CallTelemetrySamplesTable.userId],
    sampledAtEpochMillis = this[CallTelemetrySamplesTable.sampledAtEpochMillis],
    roundTripTimeMs = this[CallTelemetrySamplesTable.roundTripTimeMs],
    jitterMs = this[CallTelemetrySamplesTable.jitterMs],
    uplinkPacketLossPercent = this[CallTelemetrySamplesTable.uplinkPacketLossPercent],
    downlinkPacketLossPercent = this[CallTelemetrySamplesTable.downlinkPacketLossPercent],
    uplinkBitrateKbps = this[CallTelemetrySamplesTable.uplinkBitrateKbps],
    downlinkBitrateKbps = this[CallTelemetrySamplesTable.downlinkBitrateKbps],
    networkTransport = this[CallTelemetrySamplesTable.networkTransport],
    sendCodec = this[CallTelemetrySamplesTable.sendCodec],
    receiveCodec = this[CallTelemetrySamplesTable.receiveCodec],
    networkValidated = this[CallTelemetrySamplesTable.networkValidated],
    networkMetered = this[CallTelemetrySamplesTable.networkMetered],
)
