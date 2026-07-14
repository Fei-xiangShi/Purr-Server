package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallTelemetrySample
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

class CallTelemetryRepositoryTest {
    @Test
    fun `samples are idempotent and remain ordered by capture time`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:call-telemetry-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()
        try {
            UserRepository().apply {
                upsert("user-a", "user-a", "pass-a", "A", null)
                upsert("user-b", "user-b", "pass-b", "B", null)
            }
            PairBondRepository().upsert("pair-1", "user-a", "user-b", 1L)
            CallSessionRepository().upsert(
                CallRecord(
                    callId = "call-1",
                    pairId = "pair-1",
                    roomName = "room-1",
                    createdByUserId = "user-a",
                    startedAtEpochMillis = 1_000L,
                    updatedAtEpochMillis = 5_000L,
                    state = CallState.ENDED,
                    recordingStatus = RecordingStatus.IDLE,
                    connectedAtEpochMillis = 2_000L,
                    endedAtEpochMillis = 5_000L,
                ),
            )
            val repository = CallTelemetryRepository()
            repository.append(sample(4_000L, 40.0))
            repository.append(sample(3_000L, 30.0))
            repository.append(sample(3_000L, 999.0))

            val samples = repository.findByCallId("call-1")

            assertEquals(listOf(3_000L, 4_000L), samples.map { it.sampledAtEpochMillis })
            assertEquals(30.0, samples.first().roundTripTimeMs)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private fun sample(sampledAt: Long, rtt: Double) = CallTelemetrySample(
        callId = "call-1",
        userId = "user-a",
        sampledAtEpochMillis = sampledAt,
        roundTripTimeMs = rtt,
        jitterMs = 2.0,
        uplinkPacketLossPercent = 1.0,
        downlinkPacketLossPercent = 1.5,
        uplinkBitrateKbps = 64.0,
        downlinkBitrateKbps = 96.0,
        networkTransport = "wifi",
        sendCodec = "audio/opus",
        receiveCodec = "audio/opus",
        networkValidated = true,
        networkMetered = false,
    )
}
