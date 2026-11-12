package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPasswordRecoveryRepository(
    private val dataSource: DataSource,
) : PasswordRecoveryRepository {
    private val users = JdbcAuthUserActions(dataSource)

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = users.findEmailIdentity(emailHash)

    override suspend fun findPasswordHash(userId: UUID): PasswordHash? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT ai.password_hash, ai.password_salt, ai.password_iterations
                    FROM auth_identities ai
                    JOIN users u ON u.id = ai.user_id
                    WHERE ai.user_id = ?
                      AND ai.provider = 'email'
                      AND ai.password_hash IS NOT NULL
                      AND u.deleted_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            PasswordHash(
                                hash = rs.getString("password_hash"),
                                salt = rs.getString("password_salt"),
                                iterations = rs.getInt("password_iterations"),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override suspend fun replaceResetCode(
        codeId: UUID,
        userId: UUID,
        codeHash: String,
        expiresAt: Instant,
    ) {
        dataSource.withConnection { connection ->
            connection.inTransaction {
                prepareStatement(
                    "UPDATE password_reset_codes SET consumed_at = now() WHERE user_id = ? AND consumed_at IS NULL",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
                prepareStatement(
                    "INSERT INTO password_reset_codes (id, user_id, code_hash, expires_at) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, codeId)
                    statement.setObject(2, userId)
                    statement.setString(3, codeHash)
                    statement.setTimestamp(4, Timestamp.from(expiresAt))
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun findLiveResetCode(
        userId: UUID,
        now: Instant,
    ): ResetCodeRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, code_hash, failed_attempts
                    FROM password_reset_codes
                    WHERE user_id = ?
                      AND consumed_at IS NULL
                      AND expires_at > ?
                    ORDER BY created_at DESC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            ResetCodeRecord(
                                id = rs.getObject("id", UUID::class.java),
                                codeHash = rs.getString("code_hash"),
                                failedAttempts = rs.getInt("failed_attempts"),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override suspend fun recordFailedAttempt(codeId: UUID) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement("UPDATE password_reset_codes SET failed_attempts = failed_attempts + 1 WHERE id = ?")
                .use { statement ->
                    statement.setObject(1, codeId)
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun completeReset(
        codeId: UUID,
        userId: UUID,
        passwordHash: PasswordHash,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                // Consuming the code first under its own condition makes a double submit a no-op.
                val consumed =
                    prepareStatement(
                        "UPDATE password_reset_codes SET consumed_at = now() WHERE id = ? AND user_id = ? AND consumed_at IS NULL",
                    ).use { statement ->
                        statement.setObject(1, codeId)
                        statement.setObject(2, userId)
                        statement.executeUpdate() > 0
                    }
                if (!consumed) return@inTransaction false
                updatePassword(userId, passwordHash)
                revokeSessions(userId, keepAccessTokenHash = null)
                true
            }
        }

    override suspend fun changePassword(
        userId: UUID,
        passwordHash: PasswordHash,
        keepAccessTokenHash: String,
    ) {
        dataSource.withConnection { connection ->
            connection.inTransaction {
                updatePassword(userId, passwordHash)
                // A code requested before the change must not undo it later.
                prepareStatement(
                    "UPDATE password_reset_codes SET consumed_at = now() WHERE user_id = ? AND consumed_at IS NULL",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
                revokeSessions(userId, keepAccessTokenHash)
            }
        }
    }

    private fun Connection.updatePassword(
        userId: UUID,
        passwordHash: PasswordHash,
    ) {
        prepareStatement(
            """
            UPDATE auth_identities
            SET password_hash = ?, password_salt = ?, password_iterations = ?
            WHERE user_id = ? AND provider = 'email'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, passwordHash.hash)
            statement.setString(2, passwordHash.salt)
            statement.setInt(3, passwordHash.iterations)
            statement.setObject(4, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.revokeSessions(
        userId: UUID,
        keepAccessTokenHash: String?,
    ) {
        prepareStatement(
            """
            UPDATE user_sessions
            SET revoked_at = now()
            WHERE user_id = ?
              AND revoked_at IS NULL
              AND (?::text IS NULL OR access_token_hash <> ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, keepAccessTokenHash)
            statement.setString(3, keepAccessTokenHash)
            statement.executeUpdate()
        }
    }
}
