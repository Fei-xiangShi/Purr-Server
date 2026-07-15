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
            check(userRepository.insertIfAbsent(
                id = user.userId,
                username = user.username,
                password = user.password,
                displayName = user.displayName,
                avatarUrl = user.avatarUrl,
            )) { "Unable to create seed user ${user.userId}; check configured IDs and usernames" }
            val stored = checkNotNull(userRepository.findById(user.userId))
            check(stored.username == user.username) {
                "Seed user ${user.userId} exists with a different username"
            }
        }
        check(pairBondRepository.insertIfAbsent(
            pairId = config.pair.pairId,
            userAId = config.pair.userAId,
            userBId = config.pair.userBId,
            bondedAtEpochMillis = config.pair.bondedAtEpochMillis,
        )) { "Unable to create seed pair ${config.pair.pairId}" }
        val storedPair = checkNotNull(pairBondRepository.findByPairId(config.pair.pairId))
        check(
            storedPair.userAId == config.pair.userAId &&
                storedPair.userBId == config.pair.userBId &&
                storedPair.bondedAtEpochMillis == config.pair.bondedAtEpochMillis
        ) {
            "Seed pair ${config.pair.pairId} exists with different immutable identity data"
        }
    }
}
