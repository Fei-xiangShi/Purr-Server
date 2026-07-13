package life.fxs.purr.server.realtime

import io.lettuce.core.RedisFuture
import io.lettuce.core.pubsub.RedisPubSubAdapter
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import life.fxs.purr.server.config.RealtimeConfig
import life.fxs.purr.server.redis.RedisClientResources
import life.fxs.purr.server.redis.REDIS_COMMAND_TIMEOUT

class RedisRealtimeMessageBroker(
    config: RealtimeConfig,
    redisResources: RedisClientResources,
) : RealtimeMessageBroker {
    private val channel = config.channel
    private val closed = AtomicBoolean(false)
    private val client = redisResources.client(config.redisUri, config.redisPassword)
    private val publisherConnection = client.connect()
    private val subscriberConnection = client.connectPubSub()
    private val publisherCommands = publisherConnection.async()

    override fun subscribe(handler: (String) -> Unit) {
        check(!closed.get()) { "Realtime message broker is closed" }
        subscriberConnection.addListener(
            object : RedisPubSubAdapter<String, String>() {
                override fun message(receivedChannel: String, message: String) {
                    if (receivedChannel == channel) {
                        handler(message)
                    }
                }
            },
        )
        try {
            // Subscription setup is a startup operation. Wait here so the
            // application cannot report ready before this node is subscribed.
            subscriberConnection.async().subscribe(channel).get(
                REDIS_COMMAND_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        } catch (error: Throwable) {
            throw RealtimeBrokerException("Redis realtime subscription failed", error.unwrapCompletionFailure())
        }
    }

    override suspend fun publish(message: String) {
        check(!closed.get()) { "Realtime message broker is closed" }
        try {
            withTimeout(REDIS_COMMAND_TIMEOUT.toMillis()) {
                publisherCommands.publish(channel, message).awaitResult()
            }
        } catch (error: TimeoutCancellationException) {
            throw RealtimeBrokerException(
                "Redis realtime publication timed out after ${REDIS_COMMAND_TIMEOUT.toMillis()} ms",
                error,
            )
        } catch (error: CancellationException) {
            // A cancelled caller must remain cancelled. A Redis future that is
            // cancelled independently is a transport failure and is retryable.
            currentCoroutineContext().ensureActive()
            throw RealtimeBrokerException("Redis realtime publication was cancelled", error)
        } catch (error: Throwable) {
            throw RealtimeBrokerException("Redis realtime publication failed", error.unwrapCompletionFailure())
        }
    }

    override fun isReady(): Boolean =
        !closed.get() && publisherConnection.isOpen && subscriberConnection.isOpen

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            subscriberConnection.async().unsubscribe(channel).get(
                REDIS_COMMAND_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        }
        runCatching { subscriberConnection.close() }
        runCatching { publisherConnection.close() }
    }
}

internal class RealtimeBrokerException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

private suspend fun <T> RedisFuture<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, failure ->
        if (failure == null) {
            continuation.resumeWith(Result.success(value))
        } else {
            continuation.resumeWith(Result.failure(failure.unwrapCompletionFailure()))
        }
    }
    continuation.invokeOnCancellation {
        cancel(true)
    }
}

private fun Throwable.unwrapCompletionFailure(): Throwable = when (this) {
    is CompletionException, is ExecutionException -> cause?.unwrapCompletionFailure() ?: this
    else -> this
}
