package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @Before
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun migrationsCreateSignupAgeGateColumns() {
        assertTrue(dataSource.columnExists("users", "adult_confirmed_at"))
        assertTrue(dataSource.columnExists("users", "terms_accepted_at"))
        assertTrue(dataSource.columnExists("users", "terms_version"))
        assertTrue(dataSource.columnExists("users", "privacy_accepted_at"))
        assertTrue(dataSource.columnExists("users", "privacy_version"))
        assertTrue(dataSource.indexExists("idx_users_adult_confirmed"))
    }

    @Test
    fun createEmailUserPersistsAgeGateAndProfileBirthdate() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000801")
            val acceptedAt = Instant.parse("2026-05-13T12:00:00Z")

            val user =
                JdbcAuthRepository(dataSource).createEmailUser(
                    userId = userId,
                    identityId = UUID.fromString("00000000-0000-0000-0000-000000000802"),
                    emailHash = "email-hash-801",
                    passwordHash = PasswordHash(hash = "hash", salt = "salt", iterations = 1),
                    birthdate = LocalDate.parse("1990-05-13"),
                    displayAge = 36,
                    adultConfirmedAt = acceptedAt,
                    termsAcceptedAt = acceptedAt,
                    termsVersion = "2026-05-13",
                    privacyAcceptedAt = acceptedAt,
                    privacyVersion = "2026-05-13",
                )
            val stored = dataSource.signupSnapshot(userId)

            assertEquals(userId, user.id)
            assertEquals("active", user.status)
            assertNotNull(stored)
            assertEquals(LocalDate.parse("1990-05-13"), stored.birthdate)
            assertEquals(36, stored.displayAge)
            assertEquals(25, stored.profileCompletenessScore)
            assertEquals(acceptedAt, stored.adultConfirmedAt)
            assertEquals("2026-05-13", stored.termsVersion)
            assertEquals("2026-05-13", stored.privacyVersion)
        }
}

private data class SignupSnapshot(
    val birthdate: LocalDate,
    val displayAge: Int,
    val profileCompletenessScore: Int,
    val adultConfirmedAt: Instant,
    val termsVersion: String,
    val privacyVersion: String,
)

private fun DataSource.signupSnapshot(userId: UUID): SignupSnapshot? =
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT p.birthdate,
                       p.display_age,
                       p.profile_completeness_score,
                       u.adult_confirmed_at,
                       u.terms_version,
                       u.privacy_version
                FROM users u
                JOIN profiles p ON p.user_id = u.id
                WHERE u.id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    SignupSnapshot(
                        birthdate = rs.getDate("birthdate").toLocalDate(),
                        displayAge = rs.getInt("display_age"),
                        profileCompletenessScore = rs.getInt("profile_completeness_score"),
                        adultConfirmedAt = rs.getTimestamp("adult_confirmed_at").toInstant(),
                        termsVersion = rs.getString("terms_version"),
                        privacyVersion = rs.getString("privacy_version"),
                    )
                }
            }
    }

private fun DataSource.columnExists(
    tableName: String,
    columnName: String,
): Boolean =
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_name = ? AND column_name = ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tableName)
                statement.setString(2, columnName)
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
