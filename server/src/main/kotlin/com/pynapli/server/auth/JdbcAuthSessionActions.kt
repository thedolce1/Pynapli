package com.pynapli.server.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcAuthSessionActions(
    private val dataSource: DataSource,
) {
    suspend fun createSession(
        sessionId: UUID,
        userId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): SessionRecord =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO user_sessions (
                            id, user_id, device_id, access_token_hash, refresh_token_hash,
                            platform, app_version, access_expires_at, refresh_expires_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
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
                userStatus = "active",
            )
        }

    suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord? = findSessionByTokenHash("access_token_hash", tokenHash)

    suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? = findSessionByTokenHash("refresh_token_hash", tokenHash)

    suspend fun rotateSession(
        sessionId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): SessionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE user_sessions
                        SET access_token_hash = ?,
                            refresh_token_hash = ?,
                            access_expires_at = ?,
                            refresh_expires_at = ?,
                            last_seen_at = now()
                        WHERE id = ?
                          AND revoked_at IS NULL
                        """.trimIndent(),
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

    suspend fun revokeSession(sessionId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE user_sessions
                        SET revoked_at = now()
                        WHERE id = ?
                          AND revoked_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, sessionId)
                        statement.executeUpdate() > 0
                    }
            }
        }

    private suspend fun findSessionByTokenHash(
        column: String,
        tokenHash: String,
    ): SessionRecord? =
        withContext(Dispatchers.IO) {
            require(column == "access_token_hash" || column == "refresh_token_hash")
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT s.id, s.user_id, s.access_token_hash, s.refresh_token_hash,
                               s.access_expires_at, s.refresh_expires_at, s.revoked_at, u.status
                        FROM user_sessions s
                        JOIN users u ON u.id = s.user_id
                        WHERE s.$column = ?
                          AND u.deleted_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, tokenHash)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.toSessionRecord() else null
                        }
                    }
            }
        }
}
