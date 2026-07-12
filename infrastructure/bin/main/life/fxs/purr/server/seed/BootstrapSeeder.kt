package life.fxs.purr.server.seed

import life.fxs.purr.server.config.PurrServerConfig
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository

class BootstrapSeeder(
    private val userRepository: UserRepository,
    private val pairBondRepository: PairBondRepository,
) {
    fun seed(config: PurrServerConfig) {
        config.auth.seedUsers.forEach { user ->
            userRepository.upsert(
                id = user.userId,
                username = user.username,
                password = user.password,
                displayName = user.displayName,
                avatarUrl = user.avatarUrl,
            )
        }
        pairBondRepository.upsert(
            pairId = config.pair.pairId,
            userAId = config.pair.userAId,
            userBId = config.pair.userBId,
            bondedAtEpochMillis = config.pair.bondedAtEpochMillis,
        )
    }
}
