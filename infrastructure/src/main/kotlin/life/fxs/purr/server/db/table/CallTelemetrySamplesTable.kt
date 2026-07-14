package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object CallTelemetrySamplesTable : Table("call_telemetry_samples") {
    val callId = varchar("call_id", 128).references(CallSessionsTable.callId)
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val sampledAtEpochMillis = long("sampled_at_epoch_millis")
    val roundTripTimeMs = double("round_trip_time_ms").nullable()
    val jitterMs = double("jitter_ms").nullable()
    val uplinkPacketLossPercent = double("uplink_packet_loss_percent").nullable()
    val downlinkPacketLossPercent = double("downlink_packet_loss_percent").nullable()
    val uplinkBitrateKbps = double("uplink_bitrate_kbps").nullable()
    val downlinkBitrateKbps = double("downlink_bitrate_kbps").nullable()
    val networkTransport = varchar("network_transport", 128).nullable()
    val sendCodec = varchar("send_codec", 128).nullable()
    val receiveCodec = varchar("receive_codec", 128).nullable()
    val networkValidated = bool("network_validated")
    val networkMetered = bool("network_metered")

    override val primaryKey = PrimaryKey(callId, userId, sampledAtEpochMillis)
}
