package life.fxs.purr.server.application.account

import java.time.Instant
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.PushDeviceRecord
import life.fxs.purr.server.application.port.PushDeviceStore
import life.fxs.purr.server.application.port.PushProvider

class PushDeviceService(
    private val store: PushDeviceStore,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun register(
        userId: String,
        sessionId: String,
        installationId: String,
        provider: PushProvider,
        token: String,
    ) {
        validateInstallationId(installationId)
        validateToken(token)
        val now = nowProvider().toEpochMilli()
        store.upsert(
            PushDeviceRecord(
                installationId = installationId,
                userId = userId,
                sessionId = sessionId,
                provider = provider,
                token = token,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    fun unregister(userId: String, installationId: String) {
        validateInstallationId(installationId)
        store.remove(userId, installationId)
    }

    private fun validateInstallationId(value: String) {
        if (!value.matches(INSTALLATION_ID_PATTERN)) {
            throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Invalid push installation ID")
        }
    }

    private fun validateToken(value: String) {
        if (value.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH || value.any(Char::isWhitespace)) {
            throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Invalid push token")
        }
    }

    private companion object {
        val INSTALLATION_ID_PATTERN = Regex("[A-Za-z0-9._-]{16,128}")
        const val MIN_TOKEN_LENGTH = 16
        const val MAX_TOKEN_LENGTH = 4_096
    }
}
