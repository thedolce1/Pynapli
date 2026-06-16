package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatabaseConfigTest {
    @Test
    fun hikariConfigKeepsLocalPostgresPathWithoutCloudSqlInstance() {
        val config = hikariConfigFor(testConfig())

        assertEquals("jdbc:postgresql://localhost:5432/pynapli_test", config.jdbcUrl)
        assertEquals("true", config.dataSourceProperties["reWriteBatchedInserts"])
        assertNull(config.dataSourceProperties["socketFactory"])
        assertNull(config.dataSourceProperties["cloudSqlInstance"])
    }

    @Test
    fun hikariConfigAddsCloudSqlConnectorPropertiesWhenConfigured() {
        val config =
            hikariConfigFor(
                testConfig(
                    databaseUrl = "jdbc:postgresql:///pynapli_staging",
                    databaseCloudSqlInstance = "pynapli-staging:us-central1:pynapli-postgres",
                    databaseCloudSqlUnixSocketPath = "/cloudsql/pynapli-staging:us-central1:pynapli-postgres",
                    databaseCloudSqlRefreshStrategy = "lazy",
                ),
            )

        assertEquals("jdbc:postgresql:///pynapli_staging", config.jdbcUrl)
        assertEquals("com.google.cloud.sql.postgres.SocketFactory", config.dataSourceProperties["socketFactory"])
        assertEquals("pynapli-staging:us-central1:pynapli-postgres", config.dataSourceProperties["cloudSqlInstance"])
        assertEquals("/cloudsql/pynapli-staging:us-central1:pynapli-postgres", config.dataSourceProperties["unixSocketPath"])
        assertEquals("lazy", config.dataSourceProperties["cloudSqlRefreshStrategy"])
        assertEquals("true", config.dataSourceProperties["reWriteBatchedInserts"])
    }

    private fun testConfig(
        databaseUrl: String = "jdbc:postgresql://localhost:5432/pynapli_test",
        databaseCloudSqlInstance: String? = null,
        databaseCloudSqlUnixSocketPath: String? = null,
        databaseCloudSqlRefreshStrategy: String = "lazy",
    ): ServerConfig =
        ServerConfig(
            environment = "test",
            port = 8080,
            databaseUrl = databaseUrl,
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            databaseCloudSqlInstance = databaseCloudSqlInstance,
            databaseCloudSqlUnixSocketPath = databaseCloudSqlUnixSocketPath,
            databaseCloudSqlRefreshStrategy = databaseCloudSqlRefreshStrategy,
            redisUrl = "redis://localhost:6379",
        )
}
