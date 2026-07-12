package life.fxs.purr.server.realtime

import io.lettuce.core.pubsub.RedisPubSubAdapter
import java.util.concurrent.atomic.AtomicBoolean
import life.fxs.purr.server.config.RealtimeConfig
import life.fxs.purr.server.redis.RedisClientResources

class RedisRealtimeMessageBroker(
    config: RealtimeConfig,
    redisResources: RedisClientResources,
) : RealtimeMessageBroker {
    private val channel = config.channel
    private val closed = AtomicBoolean(false)
    private val client = redisResources.client(config.redisUri, config.redisPassword)
    private val publisherConnection = client.connect()
    private val subscriberConnection = client.connectPubSub()

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
        subscriberConnection.sync().subscribe(channel)
    }

    override fun publish(message: String) {
        check(!closed.get()) { "Realtime message broker is closed" }
        publisherConnection.sync().publish(channel, message)
    }

    override fun isReady(): Boolean =
        !closed.get() && publisherConnection.isOpen && subscriberConnection.isOpen

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { subscriberConnection.sync().unsubscribe(channel) }
        runCatching { subscriberConnection.close() }
        runCatching { publisherConnection.close() }
    }
}
