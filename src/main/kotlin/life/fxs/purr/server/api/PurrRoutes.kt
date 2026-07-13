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
import life.fxs.purr.server.model.ChangePasswordRequestDto
import life.fxs.purr.server.model.UpdateProfileRequestDto

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
            dependencies.realtimeEventPublisher.isReady() &&
            dependencies.authRateLimiter.isReady()
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
        rateLimit(AuthRateLimit) {
            put("/me/profile") {
                val request = call.receive<UpdateProfileRequestDto>()
                val user = call.requireAuthenticatedUser()
                call.respond(
                    onBlockingIo {
                        dependencies.profileService.updateDisplayName(user.userId, request.displayName).toDto()
                    },
                )
            }
        }

        rateLimit(AuthRateLimit) {
            put("/me/avatar") {
                var fileCount = 0
                var contentType: String? = null
                var bytes: ByteArray? = null
                call.receiveMultipart().forEachPart { part ->
                    try {
                        if (part is io.ktor.http.content.PartData.FileItem) {
                            if (part.name != "avatar") {
                                throw ApiException(HttpStatusCode.BadRequest, "Avatar file field is required")
                            }
                            fileCount++
                            if (fileCount > 1) {
                                throw ApiException(HttpStatusCode.BadRequest, "Only one avatar file is allowed")
                            }
                            contentType = part.contentType?.withoutParameters()?.toString()
                            bytes = part.provider().use { input ->
                                onBlockingIo { input.readAtMost(MAX_AVATAR_BYTES + 1) }
                            }
                        }
                    } finally {
                        part.dispose()
                    }
                }
                val user = call.requireAuthenticatedUser()
                val uploadBytes = bytes ?: throw ApiException(HttpStatusCode.BadRequest, "Avatar file is required")
                if (uploadBytes.size > MAX_AVATAR_BYTES) {
                    throw ApiException(HttpStatusCode.BadRequest, "Avatar must not exceed 10 MB")
                }
                call.respond(
                    onBlockingIo {
                        dependencies.avatarService.updateAvatar(
                            userId = user.userId,
                            contentType = contentType.orEmpty(),
                            bytes = uploadBytes,
                        ).toDto()
                    },
                )
            }
        }

        rateLimit(AuthRateLimit) {
            put("/me/password") {
                val request = call.receive<ChangePasswordRequestDto>()
                val user = call.requireAuthenticatedUser()
                onBlockingIo {
                    dependencies.passwordChangeService.changePassword(
                        userId = user.userId,
                        currentPassword = request.currentPassword,
                        newPassword = request.newPassword,
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }

        get("/me") {
            val user = call.requireAuthenticatedUser()
            call.respond(onBlockingIo { dependencies.pairService.requireSelfProfile(user.userId).toDto() })
        }

        get("/pair") {
            val user = call.requireAuthenticatedUser()
            call.respond(onBlockingIo { dependencies.pairService.requirePairBond(user.userId).toDto() })
        }

        post("/calls/session") {
            val request = call.receive<SessionRequestDto>()
            val user = call.requireAuthenticatedUser()
            val command = CreateCallSessionCommand(
                pairId = request.pairId,
                resumeCallId = request.resumeCallId,
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

        get("/calls/active") {
            val user = call.requireAuthenticatedUser()
            val activeCall = onBlockingIo { dependencies.callSessionService.getActiveCall(user.userId) }
            call.respond(HttpStatusCode.OK, ActiveCallResponseDto(activeCall?.toDto()))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireAuthenticatedUser(): AuthenticatedUser {
    return principal<AuthenticatedUser>() ?: throw ApiException(HttpStatusCode.Unauthorized, "Missing authenticated user")
}

@Serializable
private data class HealthResponse(
    val status: String,
)

private const val DATABASE_VALIDATION_TIMEOUT_SECONDS = 2
private const val MAX_AVATAR_BYTES = 10 * 1024 * 1024
private const val DEFAULT_CALL_HISTORY_PAGE_SIZE = 20
private const val MAX_CALL_HISTORY_PAGE_SIZE = 50
private val PrometheusContentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
