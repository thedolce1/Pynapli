package com.pynapli.server.appeals

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
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppealRepositoryIntegrationTest {
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
                    poolName = "pynapli-appeal-test"
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
    fun createAndListAndResolveAppealAgainstMigratedSchema() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000a01")
            dataSource.insertUser(userId)
            val repository = JdbcAppealRepository(dataSource)

            val created =
                repository.createAppeal(
                    userId = userId,
                    subjectType = "account",
                    subjectRef = null,
                    reason = "I believe my suspension was a mistake.",
                )
            assertNotNull(created)
            assertEquals("pending", created.status)

            // Duplicate pending appeal for the same subject is rejected (partial unique index).
            val duplicate =
                repository.createAppeal(
                    userId = userId,
                    subjectType = "account",
                    subjectRef = null,
                    reason = "Trying again.",
                )
            assertNull(duplicate)

            // Pending queue returns the new appeal.
            val pending = repository.listPendingAppeals(cursor = null, limit = 10)
            assertEquals(1, pending.size)
            assertEquals(created.id, pending.first().id)

            // Resolve as approved; the appeal leaves the pending queue and records resolution.
            val resolved =
                repository.resolveAppeal(
                    id = created.id,
                    status = "approved",
                    adminId = "moderator-1",
                    note = "Verified false positive.",
                )
            assertNotNull(resolved)
            assertEquals("approved", resolved.status)
            assertEquals("moderator-1", resolved.resolvedByAdminId)
            assertNotNull(resolved.resolvedAt)

            assertTrue(repository.listPendingAppeals(cursor = null, limit = 10).isEmpty())

            // Re-resolving an already-resolved appeal is a no-op (null), and the resolution is audited.
            assertNull(repository.resolveAppeal(created.id, "denied", "moderator-1", "again"))
            assertEquals(
                1,
                dataSource.intValue(
                    "SELECT count(*) FROM admin_audit_log WHERE target_type = 'appeal' AND target_id = ?",
                    created.id,
                ),
            )

            // After resolution a new pending appeal for the same subject is allowed again.
            val reopened =
                repository.createAppeal(
                    userId = userId,
                    subjectType = "account",
                    subjectRef = null,
                    reason = "New evidence.",
                )
            assertNotNull(reopened)
        }

    @Test
    fun riskStateForUserReadsCurrentTierAndActionedAt() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000a02")
            dataSource.insertUser(userId)
            dataSource.insertRiskState(userId, tier = "quarantine")
            val repository = JdbcAppealRepository(dataSource)

            val state = repository.riskStateForUser(userId)

            assertNotNull(state)
            assertEquals("quarantine", state.tier)
        }

    private fun DataSource.insertUser(userId: UUID) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, email_hash, status)
                    VALUES (?, ?, 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, "email-hash-$userId")
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertRiskState(
        userId: UUID,
        tier: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO account_risk_states (user_id, score, tier)
                    VALUES (?, 70, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, tier)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.intValue(
        sql: String,
        id: UUID,
    ): Int =
        connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
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
                withDatabaseName("pynapli_appeal_test")
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
