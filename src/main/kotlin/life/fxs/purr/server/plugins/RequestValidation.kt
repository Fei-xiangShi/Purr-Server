package life.fxs.purr.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import life.fxs.purr.server.model.LoginRequestDto
import life.fxs.purr.server.model.ChangePasswordRequestDto
import life.fxs.purr.server.model.RefreshRequestDto
import life.fxs.purr.server.model.SessionRequestDto
import life.fxs.purr.server.model.UpdateProfileRequestDto

fun Application.configureRequestValidation() {
    install(RequestValidation) {
        validate<LoginRequestDto> { request ->
            when {
                request.username.isBlank() || request.username.length > 64 ->
                    ValidationResult.Invalid("username must contain 1 to 64 characters")
                request.password.isEmpty() || request.password.length > 1024 ->
                    ValidationResult.Invalid("password must contain 1 to 1024 characters")
                else -> ValidationResult.Valid
            }
        }
        validate<RefreshRequestDto> { request ->
            if (request.refreshToken.length in 32..512) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid("refreshToken has an invalid length")
            }
        }
        validate<ChangePasswordRequestDto> { request ->
            when {
                request.currentPassword.isEmpty() || request.currentPassword.length > 1024 ->
                    ValidationResult.Invalid("currentPassword must contain 1 to 1024 characters")
                request.newPassword.length < 8 ->
                    ValidationResult.Invalid("newPassword must contain at least 8 characters")
                request.newPassword.toByteArray(Charsets.UTF_8).size > 72 ->
                    ValidationResult.Invalid("newPassword must not exceed 72 UTF-8 bytes")
                else -> ValidationResult.Valid
            }
        }
        validate<UpdateProfileRequestDto> { request ->
            when {
                request.displayName.trim().isEmpty() || request.displayName.trim().length > 100 ->
                    ValidationResult.Invalid("displayName must contain 1 to 100 characters")
                request.displayName.any(Char::isISOControl) ->
                    ValidationResult.Invalid("displayName must not contain control characters")
                else -> ValidationResult.Valid
            }
        }
        validate<SessionRequestDto> { request ->
            when {
                request.pairId.isBlank() || request.pairId.length > 64 ->
                    ValidationResult.Invalid("pairId must contain 1 to 64 characters")
                request.expectedCallId != null && !request.expectedCallId.matches(CALL_ID_PATTERN) ->
                    ValidationResult.Invalid("expectedCallId must be a valid call identifier")
                else -> ValidationResult.Valid
            }
        }
    }
}

private val CALL_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
