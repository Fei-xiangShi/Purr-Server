package life.fxs.purr.server.service

import life.fxs.purr.server.auth.AuthContextResolver
import life.fxs.purr.server.auth.AuthService
import life.fxs.purr.server.auth.JwtTokenService
import life.fxs.purr.server.config.PurrServerConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.livekit.JwtLiveKitTokenService
import life.fxs.purr.server.livekit.LiveKitEgressRecordingControlService
import life.fxs.purr.server.livekit.LiveKitRoomParticipantService
import life.fxs.purr.server.livekit.LiveKitWebhookService
import life.fxs.purr.server.livekit.StubRecordingControlService
import life.fxs.purr.server.repository.AuthSessionRepository
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository
import life.fxs.purr.server.seed.BootstrapSeeder

data class ServerDependencies(
    val authContextResolver: AuthContextResolver,
    val jwtTokenService: JwtTokenService,
    val authService: AuthService,
    val pairService: PairService,
    val callService: CallService,
    val liveKitWebhookService: LiveKitWebhookService,
)

object ServerDependenciesFactory {
    fun create(config: PurrServerConfig): ServerDependencies {
        DatabaseFactory(config.database).connect()

        val userRepository = UserRepository()
        val pairBondRepository = PairBondRepository()
        val authSessionRepository = AuthSessionRepository()
        val callSessionRepository = CallSessionRepository()

        BootstrapSeeder(
            userRepository = userRepository,
            pairBondRepository = pairBondRepository,
        ).seed(config)

        val jwtTokenService = JwtTokenService(config.auth)
        val authContextResolver = AuthContextResolver()
        val authService = AuthService(
            config = config.auth,
            userRepository = userRepository,
            authSessionRepository = authSessionRepository,
            jwtTokenService = jwtTokenService,
        )
        val pairService = PairService(
            pairBondRepository = pairBondRepository,
            userRepository = userRepository,
        )
        val tokenService = JwtLiveKitTokenService(config.liveKit)
        val recordingControlService = when (config.recording.provider.lowercase()) {
            "livekit" -> LiveKitEgressRecordingControlService(
                liveKitConfig = config.liveKit,
                recordingConfig = config.recording,
            )
            else -> StubRecordingControlService(config.recording)
        }
        val roomParticipantService = when (config.recording.provider.lowercase()) {
            "livekit" -> LiveKitRoomParticipantService(config.liveKit)
            else -> null
        }
        val callService = CallService(
            pairService = pairService,
            callSessionRepository = callSessionRepository,
            liveKitTokenService = tokenService,
            recordingControlService = recordingControlService,
            liveKitConfig = config.liveKit,
        )
        val liveKitWebhookService = LiveKitWebhookService(
            liveKitConfig = config.liveKit,
            callSessionRepository = callSessionRepository,
            recordingControlService = recordingControlService,
            roomParticipantService = roomParticipantService,
        )
        return ServerDependencies(
            authContextResolver = authContextResolver,
            jwtTokenService = jwtTokenService,
            authService = authService,
            pairService = pairService,
            callService = callService,
            liveKitWebhookService = liveKitWebhookService,
        )
    }
}
