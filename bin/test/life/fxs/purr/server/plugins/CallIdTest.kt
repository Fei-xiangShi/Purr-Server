package life.fxs.purr.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallIdTest {
    @Test
    fun `request IDs accept safe tracing characters only`() {
        assertTrue("req-12345678".isValidRequestId())
        assertTrue("01234567-89ab-cdef-0123-456789abcdef".isValidRequestId())
        assertFalse("short".isValidRequestId())
        assertFalse("request id with spaces".isValidRequestId())
        assertFalse("request-id/with/path".isValidRequestId())
        assertFalse("a".repeat(129).isValidRequestId())
    }
}
