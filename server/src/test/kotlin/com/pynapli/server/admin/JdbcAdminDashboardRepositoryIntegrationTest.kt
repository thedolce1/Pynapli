package com.pynapli.server.admin

import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHash
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class JdbcAdminDashboardRepositoryIntegrationTest {
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
                    poolName = "pynapli-admin-dashboard-test"
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
    fun aggregatesBacklogCountsAcrossQueues() =
        runBlocking {
            val repository = JdbcAdminDashboardRepository(dataSource)
            val reporter = createUser()
            val reportedA = createUser()
            val reportedB = createUser()
            val watchUser = createUser()
            val quarantineUser = createUser()
            val appealUser = createUser()

            dataSource.insertReport(reporter, reportedA, "open")
            dataSource.insertReport(reporter, reportedB, "open")
            dataSource.insertReport(reporter, reportedA, "under_review")
            dataSource.setRiskTier(watchUser, "watch")
            dataSource.setRiskTier(quarantineUser, "quarantine")
            dataSource.insertAppeal(appealUser, "open")
            dataSource.insertAppeal(appealUser, "escalated")

            val counts = repository.counts()

            assertEquals(2, counts.reportsOpen)
            assertEquals(1, counts.reportsUnderReview)
            assertEquals(1, counts.appealsOpen)
            assertEquals(0, counts.appealsUnderReview)
            assertEquals(1, counts.appealsEscalated)
            assertEquals(1, counts.riskWatch)
            assertEquals(1, counts.riskQuarantine)
            assertEquals(0, counts.riskFriction)
            assertEquals(0, counts.riskTerminate)
            // No held messages seeded — the join returns zero without error.
            assertEquals(0, counts.heldMessagesOpen)
        }

    private suspend fun createUser(): UUID {
        val userId = UUID.randomUUID()
        JdbcAuthRepository(dataSource).createEmailUser(
            userId = userId,
            identityId = UUID.randomUUID(),
            emailHash = "email-$userId",
            passwordHash = PasswordHash(hash = "hash", salt = "salt", iterations = 1),
            birthdate = LocalDate.parse("1990-05-13"),
            displayAge = 36,
            adultConfirmedAt = Instant.parse("2026-05-13T12:00:00Z"),
            termsAcceptedAt = Instant.parse("2026-05-13T12:00:00Z"),
            termsVersion = "2026-05-13",
            privacyAcceptedAt = Instant.parse("2026-05-13T12:00:00Z"),
            privacyVersion = "2026-05-13",
        )
        return userId
    }

    companion object {
        private val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")
        private val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_admin_dashboard_test")
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

private fun HikariDataSource.insertReport(
    reporterUserId: UUID,
    reportedUserId: UUID,
    status: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO safety_reports (
                    id, reporter_user_id, reported_user_id, target_type, target_id, category, note, status
                )
                VALUES (?, ?, ?, 'profile', ?, 'other', 'admin dashboard test', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, reporterUserId)
                statement.setObject(3, reportedUserId)
                statement.setString(4, reportedUserId.toString())
                statement.setString(5, status)
                statement.executeUpdate()
            }
    }
}

private fun HikariDataSource.setRiskTier(
    userId: UUID,
    tier: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO account_risk_states (user_id, score, tier)
                VALUES (?, 50, ?)
                ON CONFLICT (user_id) DO UPDATE SET tier = EXCLUDED.tier, score = EXCLUDED.score
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, tier)
                statement.executeUpdate()
            }
    }
}

private fun HikariDataSource.insertAppeal(
    userId: UUID,
    status: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO moderation_appeals (
                    id, user_id, action_type, target_type, status, user_reason
                )
                VALUES (?, ?, 'other', 'account', ?, 'admin dashboard test')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, status)
                statement.executeUpdate()
            }
    }
}
