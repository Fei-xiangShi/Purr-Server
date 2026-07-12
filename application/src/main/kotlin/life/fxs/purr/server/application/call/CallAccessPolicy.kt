package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallSessionStore

class CallAccessPolicy(
    private val pairService: PairService,
    private val callSessionStore: CallSessionStore,
) {
    fun requireAccessibleCall(userId: String, callId: String): CallRecord {
        val call = callSessionStore.find(callId)
            ?: throw ApplicationException(ApplicationError.NOT_FOUND, "Call not found: $callId")
        pairService.requirePairAccess(userId, call.pairId)
        return call
    }
}
