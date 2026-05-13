package com.pynapli.server.verification

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
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgeVerificationRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                poolName = "pynapli-age-verification-test"
            }
        )

        Flyway.configure()
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
    fun migrationsCreateAgeVerificationTableAndIndexes() {
        assertTrue(dataSource.tableExists("age_verifications"))
        assertTrue(dataSource.indexExists("idx_age_verifications_user_created"))
        assertTrue(dataSource.indexExists("idx_age_verifications_status_created"))
        assertTrue(dataSource.indexExists("idx_age_verifications_provider_reference"))
    }

    @Test
    fun verifiedReviewUpdatesProfileAgeVerifiedAt() = runBlocking {
        val userId = UUID.fromString("00000000-0000-0000-0000-000000000701")
        val verificationId = UUID.fromString("00000000-0000-0000-0000-000000000702")
        val requestedAt = Instant.parse("2026-05-12T10:00:00Z")
        val verifiedAt = Instant.parse("2026-05-12T12:00:00Z")
        dataSource.insertUserWithProfile(userId, birthdate = LocalDate.parse("1990-01-01"))
        val repository = JdbcAgeVerificationRepository(dataSource)

        val pending = repository.createPending(
            verificationId = verificationId,
            userId = userId,
            market = "default",
            method = "manual_review",
            provider = "internal_manual",
            providerReference = null,
            requestedAt = requestedAt
        )
        val reviewed = repository.review(
            verificationId = pending.id,
            status = "verified",
            verifiedAt = verifiedAt,
            expiresAt = null,
            reviewerId = "admin-1",
            reason = "manual age check",
            reviewedAt = verifiedAt
        )

        assertNotNull(reviewed)
        assertEquals("verified", reviewed.status)
        assertEquals(verifiedAt, reviewed.verifiedAt)
        assertEquals(verifiedAt, dataSource.profileAgeVerifiedAt(userId))
    }

    companion object {
        private val postgisImage = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres")
        private val postgres = PostgreSQLContainer<Nothing>(postgisImage).apply {
            withDatabaseName("pynapli_age_verification_test")
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

private fun DataSource.tableExists(tableName: String): Boolean =
    connection.use { connection ->
        connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

private fun DataSource.indexExists(indexName: String): Boolean =
    connection.use { connection ->
        connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
            statement.setString(1, indexName)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

private fun DataSource.insertUserWithProfile(userId: UUID, birthdate: LocalDate) {
    connection.use { connection ->
        connection.prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active')").use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO profiles (
                user_id, display_name, birthdate, display_age, moderation_status, visibility_status
            )
            VALUES (?, 'Maya', ?, 36, 'approved', 'visible')
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setDate(2, Date.valueOf(birthdate))
            statement.executeUpdate()
        }
    }
}

private fun DataSource.profileAgeVerifiedAt(userId: UUID): Instant? =
    connection.use { connection ->
        connection.prepareStatement("SELECT age_verified_at FROM profiles WHERE user_id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                rs.getTimestamp("age_verified_at")?.toInstant()
            }
        }
    }
