package life.fxs.purr.server.db

import life.fxs.purr.server.application.port.ApplicationTransaction
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedApplicationTransaction(
    private val database: Database,
) : ApplicationTransaction {
    override fun <T> execute(block: () -> T): T = transaction(database) { block() }
}
