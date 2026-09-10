package life.fxs.purr.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import life.fxs.purr.server.application.model.CreateScreenShareCommand
import life.fxs.purr.server.application.port.ScreenShareAuthorizationRequest
import life.fxs.purr.server.coroutines.onBlockingIo
import life.fxs.purr.server.model.CreateScreenShareRequestDto
import life.fxs.purr.server.model.MediaMtxAuthRequestDto
import life.fxs.purr.server.model.ScreenShareEnvelopeDto
import life.fxs.purr.server.model.ScreenShareSource
import life.fxs.purr.server.service.ServerDependencies

fun Route.registerScreenShareRoutes(dependencies: ServerDependencies) {
    post("/internal/mediamtx/auth") {
        val request = call.receive<MediaMtxAuthRequestDto>()
        val allowed = onBlockingIo {
            dependencies.screenShareAuthorizationService.authorize(
                ScreenShareAuthorizationRequest(
                    token = request.token,
                    password = request.password,
                    action = request.action,
                    protocol = request.protocol,
                    path = request.path,
                ),
            )
        }
        call.respond(if (allowed) HttpStatusCode.NoContent else HttpStatusCode.Unauthorized)
    }

    authenticate("auth-jwt") {
        post("/calls/{callId}/screen-share") {
            val callId = call.requireCallId()
            val user = call.requireAuthenticatedUser()
            val request = call.receive<CreateScreenShareRequestDto>()
            val source = ScreenShareSource.entries.firstOrNull {
                it.wireValue == request.source.lowercase()
            } ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid screen-share source")
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val result = onBlockingIo {
                dependencies.screenShareService.create(user.userId, callId, CreateScreenShareCommand(source))
            }
            call.respond(HttpStatusCode.Created, ScreenShareEnvelopeDto(result.toDto()))
        }

        get("/calls/{callId}/screen-share") {
            val callId = call.requireCallId()
            val user = call.requireAuthenticatedUser()
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val result = onBlockingIo { dependencies.screenShareService.get(user.userId, callId) }
            call.respond(HttpStatusCode.OK, ScreenShareEnvelopeDto(result?.toDto()))
        }

        delete("/calls/{callId}/screen-share") {
            val callId = call.requireCallId()
            val user = call.requireAuthenticatedUser()
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val result = onBlockingIo { dependencies.screenShareService.stop(user.userId, callId) }
            call.respond(HttpStatusCode.OK, ScreenShareEnvelopeDto(result?.toDto()))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireCallId(): String =
    parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
