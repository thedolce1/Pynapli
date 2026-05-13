package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

class JdbcAuthRepository(
    private val dataSource: DataSource
) : AuthRepository {
    override suspend fun createEmailUser(
        userId: UUID,
        identityId: UUID,
        emailHash: String,
        passwordHash: PasswordHash,
        birthdate: LocalDate,
        displayAge: Int,
        adultConfirmedAt: Instant,
        termsAcceptedAt: Instant,
        termsVersion: String,
        privacyAcceptedAt: Instant,
        privacyVersion: String
    ): UserRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO users (
                        id, email_hash, status, adult_confirmed_at,
                        terms_accepted_at, terms_version,
                        privacy_accepted_at, privacy_version
                    )
                    VALUES (?, ?, 'active', ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, emailHash)
                    statement.setTimestamp(3, Timestamp.from(adultConfirmedAt))
                    statement.setTimestamp(4, Timestamp.from(termsAcceptedAt))
                    statement.setString(5, termsVersion)
                    statement.setTimestamp(6, Timestamp.from(privacyAcceptedAt))
                    statement.setString(7, privacyVersion)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO auth_identities (
                        id, user_id, provider, provider_subject,
                        password_hash, password_salt, password_iterations, last_used_at
                    )
                    VALUES (?, ?, 'email', ?, ?, ?, ?, now())
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, identityId)
                    statement.setObject(2, userId)
                    statement.setString(3, emailHash)
                    statement.setString(4, passwordHash.hash)
                    statement.setString(5, passwordHash.salt)
                    statement.setInt(6, passwordHash.iterations)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO profiles (user_id, birthdate, display_age, profile_completeness_score)
                    VALUES (?, ?, ?, 25)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setDate(2, Date.valueOf(birthdate))
                    statement.setInt(3, displayAge)
                    statement.executeUpdate()
                }

                connection.prepareStatement("INSERT INTO discovery_settings (user_id) VALUES (?)").use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }

                connection.commit()
                UserRecord(id = userId, status = "active")
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT ai.user_id, ai.password_hash, ai.password_salt, ai.password_iterations, u.status
                FROM auth_identities ai
                JOIN users u ON u.id = ai.user_id
                WHERE ai.provider = 'email'
                  AND ai.provider_subject = ?
                  AND u.deleted_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, emailHash)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext null
                    EmailIdentityRecord(
                        userId = rs.getObject("user_id", UUID::class.java),
                        passwordHash = PasswordHash(
                            hash = rs.getString("password_hash"),
                            salt = rs.getString("password_salt"),
                            iterations = rs.getInt("password_iterations")
                        ),
                        userStatus = rs.getString("status")
                    )
                }
            }
        }
    }

    override suspend fun findUser(userId: UUID): UserRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id, status FROM users WHERE id = ? AND deleted_at IS NULL"
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext null
                    UserRecord(
                        id = rs.getObject("id", UUID::class.java),
                        status = rs.getString("status")
                    )
                }
            }
        }
    }

    override suspend fun createSession(
        sessionId: UUID,
        userId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant
    ): SessionRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO user_sessions (
                    id, user_id, device_id, access_token_hash, refresh_token_hash,
                    platform, app_version, access_expires_at, refresh_expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.setString(3, deviceId)
                statement.setString(4, accessTokenHash)
                statement.setString(5, refreshTokenHash)
                statement.setString(6, platform)
                statement.setString(7, appVersion)
                statement.setTimestamp(8, Timestamp.from(accessExpiresAt))
                statement.setTimestamp(9, Timestamp.from(refreshExpiresAt))
                statement.executeUpdate()
            }
        }

        SessionRecord(
            id = sessionId,
            userId = userId,
            accessTokenHash = accessTokenHash,
            refreshTokenHash = refreshTokenHash,
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt,
            revokedAt = null,
            userStatus = "active"
        )
    }

    override suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord? =
        findSessionByTokenHash("access_token_hash", tokenHash)

    override suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? =
        findSessionByTokenHash("refresh_token_hash", tokenHash)

    override suspend fun rotateSession(
        sessionId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant
    ): SessionRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE user_sessions
                SET access_token_hash = ?,
                    refresh_token_hash = ?,
                    access_expires_at = ?,
                    refresh_expires_at = ?,
                    last_seen_at = now()
                WHERE id = ?
                  AND revoked_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, accessTokenHash)
                statement.setString(2, refreshTokenHash)
                statement.setTimestamp(3, Timestamp.from(accessExpiresAt))
                statement.setTimestamp(4, Timestamp.from(refreshExpiresAt))
                statement.setObject(5, sessionId)
                if (statement.executeUpdate() == 0) return@withContext null
            }
        }

        findSessionByAccessTokenHash(accessTokenHash)
    }

    override suspend fun revokeSession(sessionId: UUID): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE user_sessions
                SET revoked_at = now()
                WHERE id = ?
                  AND revoked_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeUpdate() > 0
            }
        }
    }

    private suspend fun findSessionByTokenHash(column: String, tokenHash: String): SessionRecord? =
        withContext(Dispatchers.IO) {
            require(column == "access_token_hash" || column == "refresh_token_hash")
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT s.id, s.user_id, s.access_token_hash, s.refresh_token_hash,
                           s.access_expires_at, s.refresh_expires_at, s.revoked_at, u.status
                    FROM user_sessions s
                    JOIN users u ON u.id = s.user_id
                    WHERE s.$column = ?
                      AND u.deleted_at IS NULL
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@withContext null
                        SessionRecord(
                            id = rs.getObject("id", UUID::class.java),
                            userId = rs.getObject("user_id", UUID::class.java),
                            accessTokenHash = rs.getString("access_token_hash"),
                            refreshTokenHash = rs.getString("refresh_token_hash"),
                            accessExpiresAt = rs.getTimestamp("access_expires_at").toInstant(),
                            refreshExpiresAt = rs.getTimestamp("refresh_expires_at").toInstant(),
                            revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                            userStatus = rs.getString("status")
                        )
                    }
                }
            }
        }
}
