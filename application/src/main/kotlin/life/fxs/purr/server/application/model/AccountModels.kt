package life.fxs.purr.server.application.model

data class UserProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
)

data class PartnerProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val isOnline: Boolean,
    val isCallable: Boolean,
)

data class PairDetails(
    val pairId: String,
    val self: UserProfile,
    val partner: PartnerProfile,
    val bondedAtEpochMillis: Long,
)

data class AuthSessionResult(
    val accessToken: String,
    val refreshToken: String,
    val self: UserProfile,
)
