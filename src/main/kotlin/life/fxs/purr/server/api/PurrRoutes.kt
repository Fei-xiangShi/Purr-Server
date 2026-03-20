package life.fxs.purr.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import life.fxs.purr.server.auth.AuthenticatedUser
import life.fxs.purr.server.model.LoginRequestDto
import life.fxs.purr.server.model.RefreshRequestDto
import life.fxs.purr.server.model.SessionRequestDto
import life.fxs.purr.server.service.ServerDependencies

fun Route.registerPurrRoutes(dependencies: ServerDependencies) {
    get("/health") {
        call.respond(HttpStatusCode.OK, HealthResponse())
    }

    route("/auth") {
        post("/login") {
            call.respond(HttpStatusCode.OK, dependencies.authService.login(call.receive<LoginRequestDto>()))
        }
        post("/refresh") {
            call.respond(HttpStatusCode.OK, dependencies.authService.refresh(call.receive<RefreshRequestDto>()))
        }
        authenticate("auth-jwt") {
            post("/logout") {
                dependencies.authService.logout(call.requireAuthenticatedUser().sessionId)
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    authenticate("auth-jwt") {
        get("/me") {
            call.respond(dependencies.pairService.requireSelfProfile(call.requireAuthenticatedUser().userId))
        }

        get("/pair") {
            call.respond(dependencies.pairService.requirePairBond(call.requireAuthenticatedUser().userId))
        }

        post("/calls/session") {
            val request = call.receive<SessionRequestDto>()
            call.respond(HttpStatusCode.OK, dependencies.callService.createSession(call.requireAuthenticatedUser().userId, request))
        }

        post("/calls/{callId}/end") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            dependencies.callService.endCall(call.requireAuthenticatedUser().userId, callId)
            call.respond(HttpStatusCode.OK)
        }

        post("/calls/{callId}/recording/start") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            call.respond(HttpStatusCode.OK, dependencies.callService.startRecording(call.requireAuthenticatedUser().userId, callId))
        }

        post("/calls/{callId}/recording/stop") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            call.respond(HttpStatusCode.OK, dependencies.callService.stopRecording(call.requireAuthenticatedUser().userId, callId))
        }

        get("/calls/{callId}") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            call.respond(HttpStatusCode.OK, dependencies.callService.getCall(call.requireAuthenticatedUser().userId, callId))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireAuthenticatedUser(): AuthenticatedUser {
    return principal<AuthenticatedUser>() ?: throw ApiException(HttpStatusCode.Unauthorized, "Missing authenticated user")
}

@Serializable
private data class HealthResponse(
    val status: String = "ok",
)
