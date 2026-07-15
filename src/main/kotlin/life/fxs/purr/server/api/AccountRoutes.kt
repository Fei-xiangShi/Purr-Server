package life.fxs.purr.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import life.fxs.purr.server.application.port.PushProvider
import life.fxs.purr.server.application.port.AvatarUploadLimits
import life.fxs.purr.server.coroutines.onBlockingIo
import life.fxs.purr.server.model.ChangePasswordRequestDto
import life.fxs.purr.server.model.PushDeviceRegistrationDto
import life.fxs.purr.server.model.UpdateProfileRequestDto
import life.fxs.purr.server.plugins.AuthRateLimit
import life.fxs.purr.server.service.ServerDependencies

internal fun Route.registerAccountRoutes(dependencies: ServerDependencies) {
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
            val user = call.requireAuthenticatedUser()
            val profile = dependencies.avatarUploadAdmission.execute {
                val upload = call.receiveAvatarUpload()
                onBlockingIo {
                    dependencies.avatarService.updateAvatar(
                        userId = user.userId,
                        contentType = upload.contentType,
                        bytes = upload.bytes,
                    ).toDto()
                }
            }
            call.respond(profile)
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

    put("/devices/push/{installationId}") {
        val installationId = call.parameters["installationId"]
            ?: throw ApiException(HttpStatusCode.BadRequest, "Missing installationId")
        val request = call.receive<PushDeviceRegistrationDto>()
        val provider = runCatching { PushProvider.valueOf(request.provider.uppercase()) }
            .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "Unsupported push provider") }
        val user = call.requireAuthenticatedUser()
        onBlockingIo {
            dependencies.pushDeviceService.register(
                userId = user.userId,
                sessionId = user.sessionId,
                installationId = installationId,
                provider = provider,
                token = request.token,
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/devices/push/{installationId}") {
        val installationId = call.parameters["installationId"]
            ?: throw ApiException(HttpStatusCode.BadRequest, "Missing installationId")
        val user = call.requireAuthenticatedUser()
        onBlockingIo { dependencies.pushDeviceService.unregister(user.userId, installationId) }
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.receiveAvatarUpload(): AvatarUpload {
    request.headers[HttpHeaders.ContentLength]
        ?.toLongOrNull()
        ?.takeIf { it > MAX_AVATAR_MULTIPART_BYTES }
        ?.let { throw ApiException(HttpStatusCode.PayloadTooLarge, "Avatar request is too large") }

    var fileCount = 0
    var partCount = 0
    var upload: AvatarUpload? = null
    receiveMultipart().forEachPart { part ->
        try {
            partCount++
            if (partCount > MAX_AVATAR_MULTIPART_PARTS) {
                throw ApiException(HttpStatusCode.BadRequest, "Avatar multipart request has too many parts")
            }
            if (part !is PartData.FileItem || part.name != AVATAR_FIELD_NAME) {
                throw ApiException(HttpStatusCode.BadRequest, "Only the avatar file field is allowed")
            }
            fileCount++
            if (fileCount > 1) {
                throw ApiException(HttpStatusCode.BadRequest, "Only one avatar file is allowed")
            }
            val contentType = part.contentType?.withoutParameters()?.toString().orEmpty()
            if (contentType !in SUPPORTED_AVATAR_CONTENT_TYPES) {
                throw ApiException(HttpStatusCode.UnsupportedMediaType, "Avatar must be a JPEG or PNG image")
            }
            val bytes = part.provider().use { input ->
                onBlockingIo { input.readAtMost(AvatarUploadLimits.MAX_INPUT_BYTES + 1) }
            }
            if (bytes.size > AvatarUploadLimits.MAX_INPUT_BYTES) {
                throw ApiException(HttpStatusCode.PayloadTooLarge, "Avatar must not exceed 10 MB")
            }
            upload = AvatarUpload(contentType, bytes)
        } finally {
            part.dispose()
        }
    }
    return upload ?: throw ApiException(HttpStatusCode.BadRequest, "Avatar file is required")
}

private data class AvatarUpload(
    val contentType: String,
    val bytes: ByteArray,
)

private const val AVATAR_FIELD_NAME = "avatar"
private const val MAX_AVATAR_MULTIPART_BYTES = AvatarUploadLimits.MAX_INPUT_BYTES + 64 * 1024
private const val MAX_AVATAR_MULTIPART_PARTS = 2
private val SUPPORTED_AVATAR_CONTENT_TYPES = setOf("image/jpeg", "image/png")
