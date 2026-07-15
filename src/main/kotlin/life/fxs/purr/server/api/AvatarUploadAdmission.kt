package life.fxs.purr.server.api

import io.ktor.http.HttpStatusCode
import java.util.concurrent.Semaphore

class AvatarUploadAdmission(maxConcurrentUploads: Int) {
    init {
        require(maxConcurrentUploads > 0) { "Avatar upload concurrency must be positive" }
    }

    private val permits = Semaphore(maxConcurrentUploads, true)

    suspend fun <T> execute(block: suspend () -> T): T {
        if (!permits.tryAcquire()) {
            throw ApiException(
                statusCode = HttpStatusCode.ServiceUnavailable,
                message = "Avatar upload capacity is temporarily exhausted",
                code = "avatar_capacity_exhausted",
            )
        }
        return try {
            block()
        } finally {
            permits.release()
        }
    }
}
