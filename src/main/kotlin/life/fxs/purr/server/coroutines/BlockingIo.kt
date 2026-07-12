package life.fxs.purr.server.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun <T> onBlockingIo(block: () -> T): T = withContext(Dispatchers.IO) {
    block()
}
