package life.fxs.purr.server.ratelimit

import io.ktor.server.plugins.ratelimit.RateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.config.AuthRateLimitConfig
import life.fxs.purr.server.config.RateLimitProvider

class AuthRateLimiterTest {
    @Test
    fun `local provider exhausts independently per request key`() = runBlocking {
        val limiter = LocalAuthRateLimiter(config())
        val firstKey = limiter.forKey("first")
        val secondKey = limiter.forKey("second")

        assertIs<RateLimiter.State.Available>(firstKey.tryConsume(1))
        assertIs<RateLimiter.State.Available>(firstKey.tryConsume(1))
        assertIs<RateLimiter.State.Exhausted>(firstKey.tryConsume(1))
        val otherState = assertIs<RateLimiter.State.Available>(secondKey.tryConsume(1))
        assertEquals(1, otherState.remainingTokens)
    }

    @Test
    fun `redis adapter preserves the requested weight`() = runBlocking {
        var consumedWeight = 0
        val limiter = RedisTokenBucketRateLimiter { weight ->
            consumedWeight = weight
            RateLimiter.State.Available(4, 5, 123L)
        }

        val state = assertIs<RateLimiter.State.Available>(limiter.tryConsume(3))

        assertEquals(3, consumedWeight)
        assertEquals(4, state.remainingTokens)
    }

    private fun config() = AuthRateLimitConfig(
        provider = RateLimitProvider.LOCAL,
        limit = 2,
        refillPeriodSeconds = 60,
        redisUri = "redis://localhost:6379",
        redisPassword = "",
        keyPrefix = "purr:rate-limit:auth",
    )
}
