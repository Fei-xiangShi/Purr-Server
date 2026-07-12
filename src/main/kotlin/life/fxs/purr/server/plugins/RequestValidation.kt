package life.fxs.purr.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import life.fxs.purr.server.model.LoginRequestDto
import life.fxs.purr.server.model.RefreshRequestDto
import life.fxs.purr.server.model.SessionRequestDto

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
        validate<SessionRequestDto> { request ->
            when {
                request.pairId.isBlank() || request.pairId.length > 64 ->
                    ValidationResult.Invalid("pairId must contain 1 to 64 characters")
                request.resumeCallId != null && request.resumeCallId.length !in 1..128 ->
                    ValidationResult.Invalid("resumeCallId must contain 1 to 128 characters")
                else -> ValidationResult.Valid
            }
        }
    }
}
