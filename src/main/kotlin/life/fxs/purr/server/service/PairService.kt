package life.fxs.purr.server.service

import io.ktor.http.HttpStatusCode
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.model.PairBond
import life.fxs.purr.server.model.PairedPartner
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository

class PairService(
    private val pairBondRepository: PairBondRepository,
    private val userRepository: UserRepository,
) {
    fun requireSelfProfile(userId: String) = userRepository.findById(userId)?.toSelfProfile()
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Unknown userId: $userId")

    fun requirePairBond(userId: String): PairBond {
        val pairBond = pairBondRepository.findByUserId(userId)
            ?: throw ApiException(HttpStatusCode.Forbidden, "User $userId is not paired")
        val self = requireSelfProfile(userId)
        val partnerId = if (pairBond.userAId == userId) pairBond.userBId else pairBond.userAId
        val partner = userRepository.findById(partnerId)
            ?: throw ApiException(HttpStatusCode.NotFound, "Partner not found: $partnerId")
        return PairBond(
            pairId = pairBond.pairId,
            self = self,
            partner = PairedPartner(
                userId = partner.userId,
                displayName = partner.displayName,
                avatarUrl = partner.avatarUrl,
                isOnline = true,
                isCallable = true,
            ),
            bondedAtEpochMillis = pairBond.bondedAtEpochMillis,
        )
    }

    fun requirePairAccess(userId: String, pairId: String) {
        val pairBond = pairBondRepository.findByPairId(pairId)
            ?: throw ApiException(HttpStatusCode.BadRequest, "Unknown pairId: $pairId")
        if (userId != pairBond.userAId && userId != pairBond.userBId) {
            throw ApiException(HttpStatusCode.Forbidden, "User $userId is not part of pair $pairId")
        }
    }
}
