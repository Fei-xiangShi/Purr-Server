package life.fxs.purr.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import life.fxs.purr.server.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

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
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            },
        )
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
        flyway.migrate()
        val database = Database.connect(dataSource)
        return DatabaseResources(
            dataSource = dataSource,
            database = database,
        )
    }
}

data class DatabaseResources(
    val dataSource: DataSource,
    val database: Database,
)
