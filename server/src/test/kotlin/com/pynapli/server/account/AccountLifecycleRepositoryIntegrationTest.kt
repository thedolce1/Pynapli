package com.pynapli.server.account

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AccountLifecycleRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 2
                    poolName = "pynapli-account-lifecycle-test"
                },
            )

        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun createDataExportRequestPersistsLatestRequest() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000911")
            dataSource.insertUserWithProfile(userId, "Maya")
            val repository = JdbcAccountLifecycleRepository(dataSource)

            val created = repository.createDataExportRequest(userId)
            val latest = repository.latestDataExportRequest(userId)

            assertEquals(created.id, latest?.id)
            assertEquals("requested", latest?.status)
            assertEquals(userId, latest?.userId)
        }

    @Test
    fun deleteAccountRemovesDiscoveryRevokesSessionsAndPreservesSafetyReports() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000921")
            val reporterId = UUID.fromString("00000000-0000-0000-0000-000000000922")
            dataSource.insertUserWithProfile(userId, "Maya")
            dataSource.insertUserWithProfile(reporterId, "Reporter")
            dataSource.insertActiveSession(userId)
            dataSource.insertActiveTravelLocation(userId)
            dataSource.insertSafetyReport(reporterId, userId)
            val repository = JdbcAccountLifecycleRepository(dataSource)

            val result = repository.deleteAccount(userId)

            assertEquals(1, result?.revokedSessionCount)
            assertEquals("deleted", dataSource.stringValue("SELECT status FROM users WHERE id = ?", userId))
            assertNotNull(dataSource.timestampValue("SELECT deleted_at FROM users WHERE id = ?", userId))
            assertEquals("hidden", dataSource.stringValue("SELECT visibility_status FROM profiles WHERE user_id = ?", userId))
            assertEquals(false, dataSource.booleanValue("SELECT grid_visible FROM discovery_settings WHERE user_id = ?", userId))
            assertEquals(false, dataSource.booleanValue("SELECT swipe_visible FROM discovery_settings WHERE user_id = ?", userId))
            assertEquals(false, dataSource.booleanValue("SELECT events_visible FROM discovery_settings WHERE user_id = ?", userId))
            assertEquals("cleared", dataSource.stringValue("SELECT status FROM travel_locations WHERE user_id = ?", userId))
            assertNotNull(dataSource.timestampValue("SELECT revoked_at FROM user_sessions WHERE user_id = ?", userId))
            assertEquals(1, dataSource.intValue("SELECT count(*) FROM safety_reports WHERE reported_user_id = ?", userId))
            assertEquals(1, dataSource.intValue("SELECT count(*) FROM account_deletion_events WHERE user_id = ?", userId))
        }

    private fun DataSource.insertUserWithProfile(
        userId: UUID,
        displayName: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, status)
                    VALUES (?, 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO profiles (user_id, display_name, moderation_status, visibility_status)
                    VALUES (?, ?, 'approved', 'visible')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, displayName)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO discovery_settings (user_id, grid_visible, swipe_visible, events_visible)
                    VALUES (?, true, true, true)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertActiveSession(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_sessions (
                        id,
                        user_id,
                        access_token_hash,
                        refresh_token_hash,
                        access_expires_at,
                        refresh_expires_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.setString(3, "access-$userId")
                    statement.setString(4, "refresh-$userId")
                    statement.setTimestamp(5, Timestamp.from(Instant.parse("2026-05-26T13:00:00Z")))
                    statement.setTimestamp(6, Timestamp.from(Instant.parse("2026-06-26T12:00:00Z")))
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertActiveTravelLocation(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO travel_locations (id, user_id, city, region, country, geom, status)
                    VALUES (?, ?, 'Toronto', 'ON', 'CA', ST_SetSRID(ST_MakePoint(-79.3832, 43.6532), 4326), 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertSafetyReport(
        reporterId: UUID,
        reportedId: UUID,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO safety_reports (
                        id,
                        reporter_user_id,
                        reported_user_id,
                        target_type,
                        category,
                        status
                    )
                    VALUES (?, ?, ?, 'profile', 'spam_or_fake_profile', 'open')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, reporterId)
                    statement.setObject(3, reportedId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.stringValue(
        sql: String,
        userId: UUID,
    ): String? = queryValue(sql, userId) { getString(1) }

    private fun DataSource.booleanValue(
        sql: String,
        userId: UUID,
    ): Boolean? = queryValue(sql, userId) { getBoolean(1) }

    private fun DataSource.timestampValue(
        sql: String,
        userId: UUID,
    ): Timestamp? = queryValue(sql, userId) { getTimestamp(1) }

    private fun DataSource.intValue(
        sql: String,
        userId: UUID,
    ): Int = queryValue(sql, userId) { getInt(1) } ?: 0

    private fun <T> DataSource.queryValue(
        sql: String,
        userId: UUID,
        read: java.sql.ResultSet.() -> T,
    ): T? =
        connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.read() else null
                }
            }
        }

    companion object {
        private val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")

        private val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_account_lifecycle_test")
                withUsername("pynapli")
                withPassword("pynapli_test_password")
            }

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            postgres.start()
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            postgres.stop()
        }
    }
}
