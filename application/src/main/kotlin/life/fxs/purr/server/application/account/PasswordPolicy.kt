package life.fxs.purr.server.application.account

import java.nio.charset.StandardCharsets
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException

class PasswordPolicy(
    private val minimumCharacters: Int = MINIMUM_CHARACTERS,
    private val maximumUtf8Bytes: Int = MAXIMUM_UTF8_BYTES,
) {
    fun validate(password: String) {
        when {
            password.length < minimumCharacters -> throw ApplicationException(
                ApplicationError.INVALID_ARGUMENT,
                "New password must contain at least $minimumCharacters characters",
            )
            password.toByteArray(StandardCharsets.UTF_8).size > maximumUtf8Bytes -> throw ApplicationException(
                ApplicationError.INVALID_ARGUMENT,
                "New password must not exceed $maximumUtf8Bytes UTF-8 bytes",
            )
        }
    }

    companion object {
        const val MINIMUM_CHARACTERS = 8
        const val MAXIMUM_UTF8_BYTES = 72
    }
}
