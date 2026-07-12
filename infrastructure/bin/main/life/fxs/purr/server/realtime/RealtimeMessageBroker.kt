package life.fxs.purr.server.realtime

interface RealtimeMessageBroker : AutoCloseable {
    fun subscribe(handler: (String) -> Unit)
    fun publish(message: String)
    fun isReady(): Boolean
}
