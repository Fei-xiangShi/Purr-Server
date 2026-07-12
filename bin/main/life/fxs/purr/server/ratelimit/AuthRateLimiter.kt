package life.fxs.purr.server.ratelimit

import io.ktor.server.plugins.ratelimit.RateLimiter
import io.lettuce.core.ScriptOutputType
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import life.fxs.purr.server.config.AuthRateLimitConfig
import life.fxs.purr.server.config.RateLimitProvider
import life.fxs.purr.server.redis.RedisClientResources

interface AuthRateLimiter : AutoCloseable {
    val limit: Int

    fun forKey(requestKey: String): RateLimiter

    fun isReady(): Boolean

    override fun close() = Unit
}

object AuthRateLimiterFactory {
    fun create(
        config: AuthRateLimitConfig,
        redisResources: RedisClientResources,
    ): AuthRateLimiter = when (config.provider) {
        RateLimitProvider.LOCAL -> LocalAuthRateLimiter(config)
        RateLimitProvider.REDIS -> RedisAuthRateLimiter(config, redisResources)
    }
}

internal class LocalAuthRateLimiter(
    private val config: AuthRateLimitConfig,
) : AuthRateLimiter {
    override val limit: Int = config.limit

    override fun forKey(requestKey: String): RateLimiter = RateLimiter.default(
        limit = config.limit,
        refillPeriod = config.refillPeriodSeconds.seconds,
    )

    override fun isReady(): Boolean = true
}

internal class RedisAuthRateLimiter(
    private val config: AuthRateLimitConfig,
    redisResources: RedisClientResources,
) : AuthRateLimiter {
    override val limit: Int = config.limit

    private val closed = AtomicBoolean(false)
    private val client = redisResources.client(config.redisUri, config.redisPassword)
    private val connection = client.connect()
    private val commands = connection.sync()

    override fun forKey(requestKey: String): RateLimiter = RedisTokenBucketRateLimiter(
        consume = { weight -> consume(requestKey.sha256(), weight) },
    )

    override fun isReady(): Boolean = !closed.get() && connection.isOpen

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { connection.close() }
    }

    private suspend fun consume(keyHash: String, weight: Int): RateLimiter.State {
        check(!closed.get()) { "Authentication rate limiter is closed" }
        if (weight <= 0) {
            return RateLimiter.State.Available(config.limit, config.limit, System.currentTimeMillis())
        }
        if (weight > config.limit) {
            return RateLimiter.State.Exhausted(config.refillPeriodSeconds.seconds)
        }
        val result = withContext(Dispatchers.IO) {
            commands.eval<List<Any>>(
                TOKEN_BUCKET_SCRIPT,
                ScriptOutputType.MULTI,
                arrayOf("${config.keyPrefix}:$keyHash"),
                config.limit.toString(),
                (config.refillPeriodSeconds * MILLIS_PER_SECOND).toString(),
                weight.toString(),
            )
        }
        val allowed = result.longAt(0) == 1L
        val remaining = result.longAt(1).coerceIn(0, config.limit.toLong()).toInt()
        val retryAfterMillis = result.longAt(2).coerceAtLeast(1L)
        val refillAtEpochMillis = result.longAt(3)
        return if (allowed) {
            RateLimiter.State.Available(remaining, config.limit, refillAtEpochMillis)
        } else {
            RateLimiter.State.Exhausted(retryAfterMillis.milliseconds)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        val TOKEN_BUCKET_SCRIPT =
            """
            local capacity = tonumber(ARGV[1])
            local period = tonumber(ARGV[2])
            local weight = tonumber(ARGV[3])
            local redisTime = redis.call('TIME')
            local now = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)
            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'updated_at')
            local tokens = tonumber(bucket[1]) or capacity
            local updatedAt = tonumber(bucket[2]) or now
            local elapsed = math.max(0, now - updatedAt)
            tokens = math.min(capacity, tokens + (elapsed * capacity / period))
            local allowed = 0
            if tokens >= weight then
                tokens = tokens - weight
                allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updated_at', tostring(now))
            redis.call('PEXPIRE', KEYS[1], period * 2)
            local missing = math.max(0, weight - tokens)
            local retryAfter = math.max(1, math.ceil(missing * period / capacity))
            local refillAt = now + math.ceil((capacity - tokens) * period / capacity)
            return { allowed, math.floor(tokens), retryAfter, refillAt }
            """.trimIndent()
    }
}

internal fun interface RedisTokenBucketRateLimiterConsume {
    suspend fun invoke(weight: Int): RateLimiter.State
}

internal class RedisTokenBucketRateLimiter(
    private val consume: RedisTokenBucketRateLimiterConsume,
) : RateLimiter {
    override suspend fun tryConsume(num: Int): RateLimiter.State = consume.invoke(num)
}

private fun List<Any>.longAt(index: Int): Long = when (val value = getOrNull(index)) {
    is Number -> value.toLong()
    is String -> value.toLong()
    else -> error("Redis rate limit script returned an invalid value at index $index")
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
