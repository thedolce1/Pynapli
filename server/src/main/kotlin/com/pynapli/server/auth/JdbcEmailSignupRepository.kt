package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.lockForTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class JdbcEmailSignupRepository(
    private val dataSource: DataSource,
) : EmailSignupRepository {
    private val users = JdbcAuthUserActions(dataSource)
    private val placeholderEnsured = AtomicBoolean(false)

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = users.findEmailIdentity(emailHash)

    override suspend fun insertPendingSignup(
        pending: NewPendingSignup,
        now: Instant,
        maxLivePerAddress: Int,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                // Count-then-insert for one address runs one at a time, so the cap can't be raced.
                lockForTransaction(LOCK_NAMESPACE, pending.emailHash)
                prepareStatement(DELETE_EXPIRED_BATCH).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.executeUpdate()
                }
                val live =
                    prepareStatement("SELECT count(*) FROM pending_email_signups WHERE email_hash = ? AND expires_at > ?").use { statement ->
                        statement.setString(1, pending.emailHash)
                        statement.setTimestamp(2, Timestamp.from(now))
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                if (live >= maxLivePerAddress) return@inTransaction false
                prepareStatement(INSERT_PENDING).use { statement ->
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
                true
            }
        }

    override suspend fun findLivePendingSignup(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): PendingSignupRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, codes_issued
                    FROM pending_email_signups
                    WHERE email_hash = ?
                      AND signup_token_hash = ?
                      AND expires_at > ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, emailHash)
                    statement.setString(2, signupTokenHash)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            PendingSignupRecord(id = rs.getObject("id", UUID::class.java), codesIssued = rs.getInt("codes_issued"))
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
        maxCodes: Int,
        maxAttempts: Int,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE pending_email_signups
                    SET code_hash = ?, code_expires_at = ?, codes_issued = codes_issued + 1
                    WHERE id = ? AND codes_issued < ? AND attempts < ? AND $NOT_PLACEHOLDER
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, codeHash)
                    statement.setTimestamp(2, Timestamp.from(codeExpiresAt))
                    statement.setObject(3, pendingId)
                    statement.setInt(4, maxCodes)
                    statement.setInt(5, maxAttempts)
                    statement.executeUpdate() > 0
                }
        }

    override suspend fun consumeAttempt(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): SignupAttemptRecord =
        dataSource.withConnection { connection ->
            connection.ensurePlaceholder()
            // A second pass only when the matched row vanished between the lookup and the update (a
            // concurrent verify completed it): the lookup then finds nothing and a placeholder is hit.
            connection.spendAttempt(emailHash, signupTokenHash, now)
                ?: connection.spendAttempt(emailHash, signupTokenHash, now)
                ?: run {
                    placeholderEnsured.set(false)
                    connection.ensurePlaceholder()
                    connection.spendAttempt(emailHash, signupTokenHash, now)
                }
                ?: error("The sign-up placeholder rows are missing")
        }

    private fun Connection.spendAttempt(
        emailHash: String,
        signupTokenHash: String,
        now: Instant,
    ): SignupAttemptRecord? =
        prepareStatement(CONSUME_ATTEMPT).use { statement ->
            statement.setString(1, emailHash)
            statement.setString(2, signupTokenHash)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setObject(4, EmailSignupService.randomPlaceholderId())
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    SignupAttemptRecord(
                        id = rs.getObject("id", UUID::class.java),
                        attempts = rs.getLong("attempts"),
                        codeHash = rs.getString("code_hash"),
                        codeExpiresAt = rs.getTimestamp("code_expires_at").toInstant(),
                        birthdate = rs.getDate("birthdate").toLocalDate(),
                    )
                } else {
                    null
                }
            }
        }

    override suspend fun completeSignup(
        pendingId: UUID,
        emailHash: String,
        expectedCodeHash: String,
        maxAttempts: Int,
        userId: UUID,
        identityId: UUID,
        displayAge: Int,
        verifiedAt: Instant,
    ): UserRecord? =
        dataSource.withConnection { connection ->
            createUnlessTaken {
                connection.inTransaction {
                    // Taking the row first, under its code and its attempt budget, gives concurrent
                    // verifies exactly one winner: the loser's DELETE finds nothing once the winner commits.
                    val pending = takePending(pendingId, expectedCodeHash, maxAttempts) ?: return@inTransaction null
                    val user =
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
                    // Every other sign-up of the address (other devices, other people) ends here.
                    prepareStatement("DELETE FROM pending_email_signups WHERE email_hash = ? AND $NOT_PLACEHOLDER").use { statement ->
                        statement.setString(1, emailHash)
                        statement.executeUpdate()
                    }
                    user
                }
            }
        }

    override suspend fun markVerifiedForLocalDev(
        userId: UUID,
        verifiedAt: Instant,
    ) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("UPDATE users SET email_verified_at = ? WHERE id = ? AND email_verified_at IS NULL AND deleted_at IS NULL")
                .use { statement ->
                    statement.setTimestamp(1, Timestamp.from(verifiedAt))
                    statement.setObject(2, userId)
                    statement.executeUpdate()
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

    // The migration creates the placeholder pool; this only restores it where something removed it
    // (the integration tests truncate every table between tests). Once per repository instance.
    private fun Connection.ensurePlaceholder() {
        if (placeholderEnsured.get()) return
        prepareStatement(INSERT_PLACEHOLDERS).use { statement ->
            statement.setInt(1, EmailSignupService.PLACEHOLDER_POOL_SIZE - 1)
            statement.executeUpdate()
        }
        placeholderEnsured.set(true)
    }

    private fun Connection.takePending(
        pendingId: UUID,
        expectedCodeHash: String,
        maxAttempts: Int,
    ): TakenPendingSignup? =
        prepareStatement(
            """
            DELETE FROM pending_email_signups
            WHERE id = ? AND code_hash = ? AND attempts <= ? AND $NOT_PLACEHOLDER
            RETURNING email_hash, password_hash, password_salt, password_iterations,
                      birthdate, accepted_at, terms_version, privacy_version
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, pendingId)
            statement.setString(2, expectedCodeHash)
            statement.setInt(3, maxAttempts)
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
        const val LOCK_NAMESPACE = "pending-email-signup"

        // Piggybacks on every sign-up write (the retention job sweeps the rest). The placeholder row
        // never expires, so it is never in this set.
        val DELETE_EXPIRED_BATCH =
            """
            DELETE FROM pending_email_signups
            WHERE id IN (SELECT id FROM pending_email_signups WHERE expires_at <= ? LIMIT 100)
            """.trimIndent()

        val INSERT_PENDING =
            """
            INSERT INTO pending_email_signups (
                id, email_hash, signup_token_hash,
                password_hash, password_salt, password_iterations,
                birthdate, accepted_at, terms_version, privacy_version,
                code_hash, code_expires_at, codes_issued, attempts, expires_at, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?)
            """.trimIndent()

        // Always exactly one row: the live sign-up for (address, token) or the given placeholder.
        val CONSUME_ATTEMPT =
            """
            UPDATE pending_email_signups
            SET attempts = attempts + 1
            WHERE id = COALESCE(
                (
                    SELECT id FROM pending_email_signups
                    WHERE email_hash = ? AND signup_token_hash = ? AND expires_at > ?
                ),
                ?
            )
            RETURNING id, attempts, code_hash, code_expires_at, birthdate
            """.trimIndent()

        // The placeholder pool (V118) is exactly the rows that never expire.
        const val NOT_PLACEHOLDER = "expires_at <> 'infinity'"

        // Same rows as V118.
        val INSERT_PLACEHOLDERS =
            """
            INSERT INTO pending_email_signups (
                id, email_hash, signup_token_hash, password_hash, password_salt, password_iterations,
                birthdate, accepted_at, terms_version, privacy_version, code_hash, code_expires_at,
                codes_issued, attempts, expires_at
            )
            SELECT
                ('00000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
                rtrim(translate(encode(sha256(convert_to('pynapli-placeholder-email:' || n, 'UTF8')), 'base64'), '+/', '-_'), '='),
                rtrim(translate(encode(sha256(convert_to('pynapli-placeholder-token:' || n, 'UTF8')), 'base64'), '+/', '-_'), '='),
                'placeholder', 'placeholder', 0,
                DATE '1900-01-01', TIMESTAMPTZ 'epoch', 'placeholder', 'placeholder',
                rtrim(translate(encode(sha256(convert_to('pynapli-placeholder-code:' || n, 'UTF8')), 'base64'), '+/', '-_'), '='),
                TIMESTAMPTZ 'epoch',
                0, 0, TIMESTAMPTZ 'infinity'
            FROM generate_series(0, ?) AS n
            ON CONFLICT (id) DO NOTHING
            """.trimIndent()
    }
}
