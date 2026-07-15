package life.fxs.purr.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.http.content.forEachPart
import kotlinx.serialization.Serializable
import life.fxs.purr.server.auth.AuthenticatedUser
import life.fxs.purr.server.model.LoginRequestDto
import life.fxs.purr.server.model.RefreshRequestDto
import life.fxs.purr.server.model.SessionRequestDto
import life.fxs.purr.server.model.ActiveCallResponseDto
import life.fxs.purr.server.service.ServerDependencies
import life.fxs.purr.server.coroutines.onBlockingIo
import life.fxs.purr.server.plugins.AuthRateLimit
import life.fxs.purr.server.application.model.CallHistoryCursorCodec
import io.micrometer.prometheus.PrometheusMeterRegistry
import life.fxs.purr.server.application.model.CreateCallSessionCommand
import life.fxs.purr.server.model.CallRecordingsResponseDto
import life.fxs.purr.server.model.CallTelemetryRequestDto
import life.fxs.purr.server.application.port.CallTelemetrySample
import life.fxs.purr.server.model.ChangePasswordRequestDto
import life.fxs.purr.server.model.UpdateProfileRequestDto
import life.fxs.purr.server.model.PushDeviceRegistrationDto
import life.fxs.purr.server.application.port.PushProvider

fun Route.registerPurrRoutes(
    dependencies: ServerDependencies,
    meterRegistry: PrometheusMeterRegistry,
) {
    get("/health") {
        call.respond(HttpStatusCode.OK, HealthResponse(status = "ok"))
    }

    get("/health/live") {
        call.respond(HttpStatusCode.OK, HealthResponse(status = "ok"))
    }

    get("/health/ready") {
        val ready = onBlockingIo {
            dependencies.databaseResources.dataSource.connection.use { connection ->
                connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS)
            }
        }
        if (
            ready &&
            dependencies.durableEventSink.isReady() &&
            dependencies.authRateLimiter.isReady() &&
            dependencies.avatarStorageReadiness()
        ) {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "not_ready"))
        }
    }

    get("/metrics") {
        call.respondText(
            text = meterRegistry.scrape(),
            contentType = PrometheusContentType,
        )
    }

    rateLimit(AuthRateLimit) {
        route("/auth") {
            post("/login") {
                val request = call.receive<LoginRequestDto>()
                call.respond(
                    HttpStatusCode.OK,
                    onBlockingIo { dependencies.authService.login(request.username, request.password).toDto() },
                )
            }
            post("/refresh") {
                val request = call.receive<RefreshRequestDto>()
                call.respond(HttpStatusCode.OK, onBlockingIo { dependencies.authService.refresh(request.refreshToken).toDto() })
            }
            authenticate("auth-jwt") {
                post("/logout") {
                    val user = call.requireAuthenticatedUser()
                    onBlockingIo { dependencies.authService.logout(user.sessionId) }
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }

    authenticate("auth-jwt") {
        registerAccountRoutes(dependencies)

        post("/calls/session") {
            val request = call.receive<SessionRequestDto>()
            val user = call.requireAuthenticatedUser()
            val command = CreateCallSessionCommand(
                pairId = request.pairId,
                expectedCallId = request.expectedCallId,
                recordingConsent = request.recordingConsent,
            )
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo { dependencies.callSessionService.createSession(user.userId, command).toDto() },
            )
        }

        post("/calls/{callId}/end") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            val user = call.requireAuthenticatedUser()
            onBlockingIo { dependencies.callSessionService.endCall(user.userId, callId) }
            call.respond(HttpStatusCode.OK)
        }

        post("/calls/{callId}/recording/start") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            val user = call.requireAuthenticatedUser()
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo { dependencies.recordingCommandService.startRecording(user.userId, callId).toDto() },
            )
        }

        post("/calls/{callId}/recording/stop") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            val user = call.requireAuthenticatedUser()
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo { dependencies.recordingCommandService.stopRecording(user.userId, callId).toDto() },
            )
        }

        get("/calls/{callId}") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            val user = call.requireAuthenticatedUser()
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo { dependencies.callSessionService.getCall(user.userId, callId).toDto() },
            )
        }

        get("/calls/{callId}/recordings") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            val user = call.requireAuthenticatedUser()
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo {
                    CallRecordingsResponseDto(
                        dependencies.recordingQueryService.getRecordings(user.userId, callId).map { it.toDto() },
                    )
                },
            )
        }

        get("/calls/{callId}/details") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            val user = call.requireAuthenticatedUser()
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo { dependencies.callDetailQueryService.getDetail(user.userId, callId).toDto() },
            )
        }

        post("/calls/{callId}/telemetry") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            val user = call.requireAuthenticatedUser()
            val request = call.receive<CallTelemetryRequestDto>()
            onBlockingIo {
                dependencies.callTelemetryService.record(
                    userId = user.userId,
                    callId = callId,
                    sample = request.toTelemetrySample(callId, user.userId),
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/calls/{callId}/recordings/{recordingId}/download") {
            val callId = call.parameters["callId"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing callId")
            val recordingId = call.parameters["recordingId"]
                ?: throw ApiException(HttpStatusCode.BadRequest, "Missing recordingId")
            val user = call.requireAuthenticatedUser()
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo {
                    dependencies.recordingQueryService
                        .getRecordingDownload(user.userId, callId, recordingId)
                        .toDto()
                },
            )
        }

        get("/calls/history") {
            val user = call.requireAuthenticatedUser()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_CALL_HISTORY_PAGE_SIZE
            if (limit !in 1..MAX_CALL_HISTORY_PAGE_SIZE) {
                throw ApiException(HttpStatusCode.BadRequest, "Call history page size must be between 1 and 50")
            }
            val rawCursor = call.request.queryParameters["before"]
            val cursor = rawCursor?.let(CallHistoryCursorCodec::decode)
            if (rawCursor != null && cursor == null) {
                throw ApiException(HttpStatusCode.BadRequest, "Invalid call history cursor")
            }
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo {
                    dependencies.callHistoryQueryService.getHistory(user.userId, limit, cursor).toDto()
                },
            )
        }

        get("/calls/history/calendar") {
            val user = call.requireAuthenticatedUser()
            val from = call.requireEpochQuery("from")
            val to = call.requireEpochQuery("to")
            val zoneId = call.request.queryParameters["zoneId"]
                ?.takeIf(String::isNotBlank)
                ?: throw ApiException(HttpStatusCode.BadRequest, "Missing calendar time zone")
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo {
                    dependencies.callCalendarQueryService.getCalendar(user.userId, from, to, zoneId).toDto()
                },
            )
        }

        get("/calls/history/day") {
            val user = call.requireAuthenticatedUser()
            val from = call.requireEpochQuery("from")
            val to = call.requireEpochQuery("to")
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_CALL_HISTORY_PAGE_SIZE
            if (limit !in 1..MAX_CALL_HISTORY_PAGE_SIZE) {
                throw ApiException(HttpStatusCode.BadRequest, "Call history page size must be between 1 and 50")
            }
            val rawCursor = call.request.queryParameters["before"]
            val cursor = rawCursor?.let(CallHistoryCursorCodec::decode)
            if (rawCursor != null && cursor == null) {
                throw ApiException(HttpStatusCode.BadRequest, "Invalid call history cursor")
            }
            call.respond(
                HttpStatusCode.OK,
                onBlockingIo {
                    dependencies.callHistoryQueryService.getDay(user.userId, from, to, limit, cursor).toDto()
                },
            )
        }

        get("/calls/active") {
            val user = call.requireAuthenticatedUser()
            val activeCall = onBlockingIo { dependencies.callSessionService.getActiveCall(user.userId) }
            call.respond(HttpStatusCode.OK, ActiveCallResponseDto(activeCall?.toDto()))
        }
    }
}

internal fun io.ktor.server.application.ApplicationCall.requireAuthenticatedUser(): AuthenticatedUser {
    return principal<AuthenticatedUser>() ?: throw ApiException(HttpStatusCode.Unauthorized, "Missing authenticated user")
}

private fun io.ktor.server.application.ApplicationCall.requireEpochQuery(name: String): Long =
    request.queryParameters[name]?.toLongOrNull()
        ?: throw ApiException(HttpStatusCode.BadRequest, "Missing or invalid $name")

private fun CallTelemetryRequestDto.toTelemetrySample(callId: String, userId: String) = CallTelemetrySample(
    callId = callId,
    userId = userId,
    sampledAtEpochMillis = sampledAtEpochMillis,
    roundTripTimeMs = roundTripTimeMs,
    jitterMs = jitterMs,
    uplinkPacketLossPercent = uplinkPacketLossPercent,
    downlinkPacketLossPercent = downlinkPacketLossPercent,
    uplinkBitrateKbps = uplinkBitrateKbps,
    downlinkBitrateKbps = downlinkBitrateKbps,
    networkTransport = networkTransport,
    sendCodec = sendCodec,
    receiveCodec = receiveCodec,
    networkValidated = networkValidated,
    networkMetered = networkMetered,
)

@Serializable
private data class HealthResponse(
    val status: String,
)

private const val DATABASE_VALIDATION_TIMEOUT_SECONDS = 2
private const val DEFAULT_CALL_HISTORY_PAGE_SIZE = 20
private const val MAX_CALL_HISTORY_PAGE_SIZE = 50
private val PrometheusContentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
