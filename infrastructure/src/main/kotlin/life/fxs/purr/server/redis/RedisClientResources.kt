package life.fxs.purr.server.redis

import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RedisClientResources : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<RedisEndpoint, RedisClient>()

    fun client(uri: String, password: String): RedisClient {
        check(!closed.get()) { "Redis client resources are closed" }
        val endpoint = RedisEndpoint(uri, password)
        return clients.computeIfAbsent(endpoint, ::createClient)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        clients.values.forEach { client ->
            runCatching { client.shutdown(SHUTDOWN_QUIET_PERIOD, SHUTDOWN_TIMEOUT) }
                .onFailure { error -> failure?.addSuppressed(error) ?: run { failure = error } }
        }
        clients.clear()
        failure?.let { throw it }
    }

    private fun createClient(endpoint: RedisEndpoint): RedisClient {
        val redisUri = RedisURI.builder(RedisURI.create(endpoint.uri)).run {
            if (endpoint.password.isNotEmpty()) {
                withPassword(endpoint.password.toCharArray())
            }
            withTimeout(REDIS_COMMAND_TIMEOUT)
            build()
        }
        return RedisClient.create(redisUri).also { client ->
            client.setOptions(
                ClientOptions.builder()
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(
                        SocketOptions.builder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .keepAlive(true)
                            .build(),
                    )
                    // RedisURI timeouts govern blocking commands. TimeoutOptions
                    // also enforces the same deadline for asynchronous commands.
                    .timeoutOptions(TimeoutOptions.enabled(REDIS_COMMAND_TIMEOUT))
                    .build(),
            )
        }
    }

    private data class RedisEndpoint(
        val uri: String,
        val password: String,
    )

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val SHUTDOWN_QUIET_PERIOD: Duration = Duration.ZERO
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

internal val REDIS_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(3)
