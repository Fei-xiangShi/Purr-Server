package life.fxs.purr.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.response.respond
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.model.ErrorResponse

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                cause.statusCode,
                ErrorResponse(cause.code, cause.message, call.callId),
            )
        }
        exception<ApplicationException> { call, cause ->
            val (status, code) = cause.error.toHttpError()
            call.respond(status, ErrorResponse(code, cause.message, call.callId))
        }
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("validation_error", cause.reasons.joinToString(), call.callId),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("bad_request", cause.message ?: "Bad request", call.callId),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled request failure; requestId=${call.callId}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", "Internal server error", call.callId),
            )
        }
    }
}

private fun ApplicationError.toHttpError(): Pair<HttpStatusCode, String> = when (this) {
    ApplicationError.UNAUTHENTICATED -> HttpStatusCode.Unauthorized to "unauthorized"
    ApplicationError.FORBIDDEN -> HttpStatusCode.Forbidden to "forbidden"
    ApplicationError.NOT_FOUND -> HttpStatusCode.NotFound to "not_found"
    ApplicationError.INVALID_ARGUMENT -> HttpStatusCode.BadRequest to "bad_request"
    ApplicationError.CONFLICT -> HttpStatusCode.Conflict to "conflict"
    ApplicationError.EXTERNAL_DEPENDENCY -> HttpStatusCode.BadGateway to "dependency_failure"
    ApplicationError.TEMPORARILY_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable to "temporarily_unavailable"
}
