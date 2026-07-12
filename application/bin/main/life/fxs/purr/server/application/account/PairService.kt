package life.fxs.purr.server.application.account

import java.time.Instant
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.model.PairDetails
import life.fxs.purr.server.application.model.PartnerProfile
import life.fxs.purr.server.application.model.UserProfile
import life.fxs.purr.server.application.port.PairStore
import life.fxs.purr.server.application.port.PresenceStore
import life.fxs.purr.server.application.port.UserAccountStore

class PairService(
    private val pairStore: PairStore,
    private val userAccountStore: UserAccountStore,
    private val presenceStore: PresenceStore? = null,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun requireSelfProfile(userId: String): UserProfile {
        val user = userAccountStore.findById(userId)
            ?: throw ApplicationException(ApplicationError.UNAUTHENTICATED, "Unknown userId: $userId")
        return UserProfile(user.userId, user.displayName, user.avatarUrl)
    }

    fun requirePairBond(userId: String): PairDetails {
        val pair = pairStore.findByUserId(userId)
            ?: throw ApplicationException(ApplicationError.FORBIDDEN, "User $userId is not paired")
        val self = requireSelfProfile(userId)
        val partnerId = if (pair.userAId == userId) pair.userBId else pair.userAId
        val partner = userAccountStore.findById(partnerId)
            ?: throw ApplicationException(ApplicationError.NOT_FOUND, "Partner not found: $partnerId")
        val partnerOnline = presenceStore?.isOnline(partnerId, nowProvider().toEpochMilli()) == true
        return PairDetails(
            pairId = pair.pairId,
            self = self,
            partner = PartnerProfile(
                userId = partner.userId,
                displayName = partner.displayName,
                avatarUrl = partner.avatarUrl,
                isOnline = partnerOnline,
                isCallable = partnerOnline,
            ),
            bondedAtEpochMillis = pair.bondedAtEpochMillis,
        )
    }

    fun requirePartnerUserId(userId: String): String {
        val pair = pairStore.findByUserId(userId)
            ?: throw ApplicationException(ApplicationError.FORBIDDEN, "User $userId is not paired")
        return if (pair.userAId == userId) pair.userBId else pair.userAId
    }

    fun requirePairId(userId: String): String = pairStore.findByUserId(userId)?.pairId
        ?: throw ApplicationException(ApplicationError.FORBIDDEN, "User $userId is not paired")

    fun requirePairAccess(userId: String, pairId: String) {
        val pair = pairStore.findByPairId(pairId)
            ?: throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Unknown pairId: $pairId")
        if (userId != pair.userAId && userId != pair.userBId) {
            throw ApplicationException(ApplicationError.FORBIDDEN, "User $userId is not part of pair $pairId")
        }
    }

    fun requirePairUserIds(pairId: String): Set<String> {
        val pair = pairStore.findByPairId(pairId)
            ?: throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Unknown pairId: $pairId")
        return setOf(pair.userAId, pair.userBId)
    }
}
