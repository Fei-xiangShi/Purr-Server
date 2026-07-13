package life.fxs.purr.server.realtime

interface RealtimeMessageBroker : AutoCloseable {
    fun subscribe(handler: (String) -> Unit)

    /** Returns after the broker acknowledges the publication. */
    suspend fun publish(message: String)

    fun isReady(): Boolean
}
