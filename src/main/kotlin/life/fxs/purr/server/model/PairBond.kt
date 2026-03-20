package life.fxs.purr.server.model

import kotlinx.serialization.Serializable

@Serializable
data class PairBond(
    val pairId: String,
    val self: SelfProfile,
    val partner: PairedPartner,
    val bondedAtEpochMillis: Long,
)
