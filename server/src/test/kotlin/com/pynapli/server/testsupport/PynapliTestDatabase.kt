package com.pynapli.server.testsupport

import com.pynapli.server.dependencies.RedisCache
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * Shared, JVM-wide Testcontainers infrastructure for the server integration suite.
 *
 * Every `*IntegrationTest` used to start its OWN Postgres (+ optionally Redis) container in
 * `beforeSpec` and run `flyway.clean() + migrate()` on EVERY test — ~25 container lifecycles per run
 * and a full ~80-migration schema rebuild per test. The suite runs sequentially (no Gradle parallel
 * forks; Kotest defaults to sequential), so a single shared container is safe and far cheaper:
 *
 *  - Postgres/PostGIS starts ONCE (first access) and Flyway migrates ONCE.
 *  - Per-test isolation is a fast [reset] (`TRUNCATE` of the data tables), not a clean + migrate.
 *  - Redis starts lazily — only the discovery grid/swipe test needs it.
 *
 * Containers are never explicitly stopped: Testcontainers' Ryuk reaps them when the test JVM exits.
 * Drive it from a `beforeTest` hook: `beforeTest { PynapliTestDatabase.reset() }`.
 */
object PynapliTestDatabase {
    private val postgisImage =
        DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres")

    private val postgres: PostgreSQLContainer<Nothing> =
        PostgreSQLContainer<Nothing>(postgisImage).apply {
            withDatabaseName("pynapli_test")
            withUsername("pynapli")
            withPassword("pynapli_test_password")
        }

    private val redisContainer: GenericContainer<Nothing> =
        GenericContainer<Nothing>(DockerImageName.parse("redis:7-alpine"))
            .apply { withExposedPorts(6379) }

    /** One shared pool; the schema has been migrated exactly once by the time this returns. */
    val dataSource: DataSource by lazy { startPostgresAndMigrate() }

    private fun startPostgresAndMigrate(): HikariDataSource {
        postgres.start()
        val ds =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 16
                    poolName = "pynapli-test"
                },
            )
        Flyway
            .configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return ds
    }

    @Volatile private var redisStarted = false

    private fun ensureRedis(): GenericContainer<Nothing> {
        if (!redisStarted) {
            synchronized(this) {
                if (!redisStarted) {
                    redisContainer.start()
                    redisStarted = true
                }
            }
        }
        return redisContainer
    }

    /** Redis connection URI for the shared container (starts Redis on first use). */
    fun redisUri(): String {
        val container = ensureRedis()
        return "redis://${container.host}:${container.getMappedPort(6379)}"
    }

    fun redisCache(): RedisCache = RedisCache(redisUri())

    /** Data tables to wipe between tests: everything in `public` except Flyway + PostGIS ref data. */
    private val dataTables: List<String> by lazy {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT tablename
                    FROM pg_tables
                    WHERE schemaname = 'public'
                      AND tablename NOT IN ('flyway_schema_history', 'spatial_ref_sys', 'schema_marker')
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString("tablename")) }
                    }
                }
        }
    }

    /**
     * Fast per-test reset: `TRUNCATE` all data tables (keeps the already-migrated schema). Orders of
     * magnitude cheaper than `flyway.clean() + migrate()`. Does NOT touch Redis — call [flushRedis]
     * from the (single) test that uses it.
     */
    fun reset() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE ${dataTables.joinToString(", ") { "\"$it\"" }} RESTART IDENTITY CASCADE",
                )
            }
        }
    }

    /** Clears all Redis state (starts Redis if it is not already running). */
    fun flushRedis() {
        ensureRedis().execInContainer("redis-cli", "FLUSHALL")
    }
}
