package life.fxs.purr.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import life.fxs.purr.server.ratelimit.AuthRateLimiter
import io.ktor.server.response.header

val AuthRateLimit = RateLimitName("auth")

fun Application.configureRateLimiting(authRateLimiter: AuthRateLimiter) {
    install(RateLimit) {
        register(AuthRateLimit) {
            requestKey { call -> call.request.origin.remoteHost }
            rateLimiter { _, requestKey -> authRateLimiter.forKey(requestKey.toString()) }
            modifyResponse { call, state ->
                call.response.header(RATE_LIMIT_HEADER, authRateLimiter.limit.toString())
                when (state) {
                    is io.ktor.server.plugins.ratelimit.RateLimiter.State.Available -> {
                        call.response.header(RATE_LIMIT_REMAINING_HEADER, state.remainingTokens.toString())
                        val resetAfterSeconds = (
                            (state.refillAtTimeMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 999L
                            ) / 1_000L
                        call.response.header(RATE_LIMIT_RESET_HEADER, resetAfterSeconds.toString())
                    }
                    is io.ktor.server.plugins.ratelimit.RateLimiter.State.Exhausted -> {
                        val retryAfterSeconds = (state.toWait.inWholeMilliseconds + 999L) / 1_000L
                        call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.coerceAtLeast(1L).toString())
                    }
                }
            }
        }
    }
}

private const val RATE_LIMIT_HEADER = "RateLimit-Limit"
private const val RATE_LIMIT_REMAINING_HEADER = "RateLimit-Remaining"
private const val RATE_LIMIT_RESET_HEADER = "RateLimit-Reset"
