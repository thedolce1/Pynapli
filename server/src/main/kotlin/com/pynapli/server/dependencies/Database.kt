package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

class Database(config: ServerConfig) : AutoCloseable {
    val dataSource: DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = config.databaseMaximumPoolSize
            minimumIdle = config.databaseMinimumIdle.coerceAtMost(config.databaseMaximumPoolSize)
            connectionTimeout = config.databaseConnectionTimeoutMillis
            validationTimeout = 3_000
            poolName = "pynapli-postgres"
        }
    )

    init {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    override fun close() {
        (dataSource as HikariDataSource).close()
    }
}
