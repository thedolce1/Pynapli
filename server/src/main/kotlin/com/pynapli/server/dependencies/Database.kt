package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.profiling.DatabasePoolSnapshot
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

    fun poolSnapshot(): DatabasePoolSnapshot? {
        val hikariDataSource = dataSource as? HikariDataSource ?: return null
        val pool = hikariDataSource.hikariPoolMXBean ?: return null
        return DatabasePoolSnapshot(
            activeConnections = pool.activeConnections,
            idleConnections = pool.idleConnections,
            totalConnections = pool.totalConnections,
            threadsAwaitingConnection = pool.threadsAwaitingConnection,
            maximumPoolSize = hikariDataSource.maximumPoolSize,
            minimumIdle = hikariDataSource.minimumIdle
        )
    }

    override fun close() {
        (dataSource as HikariDataSource).close()
    }
}
