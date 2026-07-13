package life.fxs.purr.server.api

import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BoundedInputReaderTest {
    @Test
    fun `reader terminates when EOF follows available bytes`() {
        val source = byteArrayOf(1, 2, 3, 4)

        val result = completesWithinTimeout {
            buildPacket { writeFully(source) }.use { input ->
                input.readAtMost(source.size + 1)
            }
        }

        assertContentEquals(source, result)
    }

    @Test
    fun `reader terminates for empty input`() {
        val result = completesWithinTimeout {
            buildPacket {}.use { input -> input.readAtMost(1) }
        }

        assertContentEquals(ByteArray(0), result)
    }

    @Test
    fun `reader does not consume beyond its byte limit`() {
        val source = ByteArray(11) { it.toByte() }
        val input = buildPacket { writeFully(source) }

        try {
            val result = completesWithinTimeout { input.readAtMost(10) }

            assertContentEquals(source.copyOf(10), result)
            assertEquals(1L, input.remaining)
        } finally {
            input.close()
        }
    }

    private fun <T> completesWithinTimeout(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bounded-input-reader-test").apply { isDaemon = true }
        }
        return try {
            executor.submit(Callable(block)).get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 2L
    }
}
