package life.fxs.purr.server.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink

class CompositeRealtimeEventSinkTest {
    @Test
    fun `transport failures are aggregated after every transport is attempted`() = runBlocking {
        val attempted = mutableListOf<String>()
        val sink = CompositeRealtimeEventSink(
            listOf(
                recordingSink("websocket", attempted) { error("websocket failed") },
                recordingSink("push", attempted),
            ),
        )

        assertFailsWith<IllegalStateException> {
            sink.publishToUser("user-a", RealtimeEvent(type = RealtimeEvent.CALL_STARTED))
        }

        assertEquals(listOf("websocket", "push"), attempted)
    }

    @Test
    fun `cancellation stops delivery immediately and is not wrapped`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val attempted = mutableListOf<String>()
        val sink = CompositeRealtimeEventSink(
            listOf(
                recordingSink("websocket", attempted) { throw cancellation },
                recordingSink("push", attempted),
            ),
        )

        val thrown = assertFailsWith<CancellationException> {
            sink.publishToUser("user-a", RealtimeEvent(type = RealtimeEvent.CALL_STARTED))
        }

        assertEquals(cancellation, thrown)
        assertEquals(listOf("websocket"), attempted)
    }

    private fun recordingSink(
        name: String,
        attempted: MutableList<String>,
        action: () -> Unit = {},
    ) = object : RealtimeEventSink {
        override suspend fun publishToUser(userId: String, event: RealtimeEvent) {
            attempted += name
            action()
        }
    }
}
