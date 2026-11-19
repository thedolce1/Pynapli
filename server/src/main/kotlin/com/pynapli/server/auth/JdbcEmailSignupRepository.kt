package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

class JdbcEmailSignupRepository(
    private val dataSource: DataSource,
) : EmailSignupRepository {
    private val users = JdbcAuthUserActions(dataSource)

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = users.findEmailIdentity(emailHash)

    override suspend fun replacePendingSignup(
        pending: NewPendingSignup,
        now: Instant,
    ) {
        dataSource.withConnection { connection ->
            connection.inTransaction {
                prepareStatement(DELETE_EXPIRED_BATCH).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.executeUpdate()
                }
                prepareStatement(UPSERT_PENDING).use { statement ->
                    statement.setObject(1, pending.id)
                    statement.setString(2, pending.emailHash)
                    statement.setString(3, pending.signupTokenHash)
                    statement.setString(4, pending.passwordHash.hash)
                    statement.setString(5, pending.passwordHash.salt)
                    statement.setInt(6, pending.passwordHash.iterations)
                    statement.setDate(7, Date.valueOf(pending.birthdate))
                    statement.setTimestamp(8, Timestamp.from(pending.acceptedAt))
                    statement.setString(9, pending.termsVersion)
                    statement.setString(10, pending.privacyVersion)
                    statement.setString(11, pending.codeHash)
                    statement.setTimestamp(12, Timestamp.from(pending.codeExpiresAt))
                    statement.setTimestamp(13, Timestamp.from(pending.expiresAt))
                    statement.setTimestamp(14, Timestamp.from(pending.createdAt))
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun findLivePendingSignup(
        emailHash: String,
        now: Instant,
    ): PendingSignupRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, signup_token_hash, code_hash, code_expires_at, failed_attempts, birthdate
                    FROM pending_email_signups
                    WHERE email_hash = ?
                      AND expires_at > ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, emailHash)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            PendingSignupRecord(
                                id = rs.getObject("id", UUID::class.java),
                                signupTokenHash = rs.getString("signup_token_hash"),
                                codeHash = rs.getString("code_hash"),
                                codeExpiresAt = rs.getTimestamp("code_expires_at").toInstant(),
                                failedAttempts = rs.getInt("failed_attempts"),
                                birthdate = rs.getDate("birthdate").toLocalDate(),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override suspend fun replaceCode(
        pendingId: UUID,
        codeHash: String,
        codeExpiresAt: Instant,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    "UPDATE pending_email_signups SET code_hash = ?, code_expires_at = ?, failed_attempts = 0 WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, codeHash)
                    statement.setTimestamp(2, Timestamp.from(codeExpiresAt))
                    statement.setObject(3, pendingId)
                    statement.executeUpdate() > 0
                }
        }

    override suspend fun recordFailedAttempt(pendingId: UUID) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("UPDATE pending_email_signups SET failed_attempts = failed_attempts + 1 WHERE id = ?")
                .use { statement ->
                    statement.setObject(1, pendingId)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun completeSignup(
        pendingId: UUID,
        expectedCodeHash: String,
        userId: UUID,
        identityId: UUID,
        displayAge: Int,
        verifiedAt: Instant,
    ): UserRecord? =
        dataSource.withConnection { connection ->
            createUnlessTaken {
                connection.inTransaction {
                    // Taking the row first, under its code, gives concurrent verifies exactly one winner:
                    // the loser's DELETE finds nothing once the winner commits.
                    val pending = takePending(pendingId, expectedCodeHash) ?: return@inTransaction null
                    insertEmailAccount(
                        userId = userId,
                        identityId = identityId,
                        emailHash = pending.emailHash,
                        passwordHash = pending.passwordHash,
                        birthdate = pending.birthdate,
                        displayAge = displayAge,
                        adultConfirmedAt = pending.acceptedAt,
                        termsAcceptedAt = pending.acceptedAt,
                        termsVersion = pending.termsVersion,
                        privacyAcceptedAt = pending.acceptedAt,
                        privacyVersion = pending.privacyVersion,
                        emailVerifiedAt = verifiedAt,
                    )
                }
            }
        }

    override suspend fun createVerifiedAccount(
        userId: UUID,
        identityId: UUID,
        emailHash: String,
        passwordHash: PasswordHash,
        birthdate: LocalDate,
        displayAge: Int,
        acceptedAt: Instant,
        termsVersion: String,
        privacyVersion: String,
        verifiedAt: Instant,
    ): UserRecord? =
        dataSource.withConnection { connection ->
            createUnlessTaken {
                connection.inTransaction {
                    insertEmailAccount(
                        userId = userId,
                        identityId = identityId,
                        emailHash = emailHash,
                        passwordHash = passwordHash,
                        birthdate = birthdate,
                        displayAge = displayAge,
                        adultConfirmedAt = acceptedAt,
                        termsAcceptedAt = acceptedAt,
                        termsVersion = termsVersion,
                        privacyAcceptedAt = acceptedAt,
                        privacyVersion = privacyVersion,
                        emailVerifiedAt = verifiedAt,
                    )
                }
            }
        }

    /** A unique violation (the address already has an account) rolls back and answers null. */
    private inline fun createUnlessTaken(create: () -> UserRecord?): UserRecord? =
        try {
            create()
        } catch (error: SQLException) {
            if (error.sqlState == UNIQUE_VIOLATION) null else throw error
        }

    private fun Connection.takePending(
        pendingId: UUID,
        expectedCodeHash: String,
    ): TakenPendingSignup? =
        prepareStatement(
            """
            DELETE FROM pending_email_signups
            WHERE id = ? AND code_hash = ?
            RETURNING email_hash, password_hash, password_salt, password_iterations,
                      birthdate, accepted_at, terms_version, privacy_version
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, pendingId)
            statement.setString(2, expectedCodeHash)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    TakenPendingSignup(
                        emailHash = rs.getString("email_hash"),
                        passwordHash =
                            PasswordHash(
                                hash = rs.getString("password_hash"),
                                salt = rs.getString("password_salt"),
                                iterations = rs.getInt("password_iterations"),
                            ),
                        birthdate = rs.getDate("birthdate").toLocalDate(),
                        acceptedAt = rs.getTimestamp("accepted_at").toInstant(),
                        termsVersion = rs.getString("terms_version"),
                        privacyVersion = rs.getString("privacy_version"),
                    )
                } else {
                    null
                }
            }
        }

    private class TakenPendingSignup(
        val emailHash: String,
        val passwordHash: PasswordHash,
        val birthdate: LocalDate,
        val acceptedAt: Instant,
        val termsVersion: String,
        val privacyVersion: String,
    )

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        // Piggybacks on every sign-up write (the retention job sweeps the rest).
        val DELETE_EXPIRED_BATCH =
            """
            DELETE FROM pending_email_signups
            WHERE id IN (SELECT id FROM pending_email_signups WHERE expires_at <= ? LIMIT 100)
            """.trimIndent()

        // One row per address: a new sign-up replaces everything about the old one, including its id
        // (which salts the code hash) and its token, so the old code and the old device are both done.
        val UPSERT_PENDING =
            """
            INSERT INTO pending_email_signups (
                id, email_hash, signup_token_hash,
                password_hash, password_salt, password_iterations,
                birthdate, accepted_at, terms_version, privacy_version,
                code_hash, code_expires_at, failed_attempts, expires_at, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            ON CONFLICT (email_hash) DO UPDATE SET
                id = EXCLUDED.id,
                signup_token_hash = EXCLUDED.signup_token_hash,
                password_hash = EXCLUDED.password_hash,
                password_salt = EXCLUDED.password_salt,
                password_iterations = EXCLUDED.password_iterations,
                birthdate = EXCLUDED.birthdate,
                accepted_at = EXCLUDED.accepted_at,
                terms_version = EXCLUDED.terms_version,
                privacy_version = EXCLUDED.privacy_version,
                code_hash = EXCLUDED.code_hash,
                code_expires_at = EXCLUDED.code_expires_at,
                failed_attempts = 0,
                expires_at = EXCLUDED.expires_at,
                created_at = EXCLUDED.created_at
            """.trimIndent()
    }
}
