package life.fxs.purr.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import java.util.concurrent.atomic.AtomicBoolean
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager

class DatabaseFactory(
    private val config: DatabaseConfig,
) {
    fun connect(): DatabaseResources {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                driverClassName = config.driverClassName
                username = config.username
                password = config.password
                maximumPoolSize = config.maximumPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                validate()
            },
        )
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
        flyway.migrate()
        val database = Database.connect(dataSource)
        TransactionManager.defaultDatabase = database
        return DatabaseResources(
            dataSource = dataSource,
            applicationTransaction = ExposedApplicationTransaction(database),
            database = database,
        )
    }
}

class DatabaseResources(
    val dataSource: DataSource,
    val applicationTransaction: ApplicationTransaction,
    private val database: Database,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            TransactionManager.closeAndUnregister(database)
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    }
}
