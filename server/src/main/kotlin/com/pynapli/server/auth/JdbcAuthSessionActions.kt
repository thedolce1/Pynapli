package com.pynapli.server.auth

import com.pynapli.server.db.withConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * SET-clause fragment that forgets a session's sealed refresh replay (see [AuthRepository.rotateSession]).
 * Every statement that revokes sessions includes it, so a revoked row holds no replay at all; the replay
 * lookup also requires `revoked_at IS NULL`, which is the actual guarantee.
 */
internal const val CLEAR_REFRESH_REPLAY_SQL =
    "previous_refresh_token_hash = NULL, replay_nonce = NULL, replay_ciphertext = NULL, replay_expires_at = NULL"

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
        presentedRefreshTokenHash: String,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        replayNonce: ByteArray,
        replayCiphertext: ByteArray,
        replayExpiresAt: Instant,
    ): SessionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                // One statement: the compare-and-swap and the replay columns land together, so the
                // presented token becomes the "previous" one exactly when it stops being current, and
                // overwrites whatever previous token (and sealed replay) the row held before.
                connection
                    .prepareStatement(
                        """
                        UPDATE user_sessions
                        SET access_token_hash = ?,
                            refresh_token_hash = ?,
                            access_expires_at = ?,
                            refresh_expires_at = ?,
                            previous_refresh_token_hash = ?,
                            replay_nonce = ?,
                            replay_ciphertext = ?,
                            replay_expires_at = ?,
                            last_seen_at = now()
                        WHERE id = ?
                          AND refresh_token_hash = ?
                          AND revoked_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, accessTokenHash)
                        statement.setString(2, refreshTokenHash)
                        statement.setTimestamp(3, Timestamp.from(accessExpiresAt))
                        statement.setTimestamp(4, Timestamp.from(refreshExpiresAt))
                        statement.setString(5, presentedRefreshTokenHash)
                        statement.setBytes(6, replayNonce)
                        statement.setBytes(7, replayCiphertext)
                        statement.setTimestamp(8, Timestamp.from(replayExpiresAt))
                        statement.setObject(9, sessionId)
                        statement.setString(10, presentedRefreshTokenHash)
                        if (statement.executeUpdate() == 0) return@withContext null
                    }
            }

            findSessionByAccessTokenHash(accessTokenHash)
        }

    suspend fun findRefreshReplay(previousRefreshTokenHash: String): RefreshReplayRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                // Read-only: a replay never changes the row. Expiry, grace and user status are judged
                // by the caller against its clock, as the current-token path does.
                connection
                    .prepareStatement(
                        """
                        SELECT s.id, s.user_id, s.access_token_hash, s.refresh_token_hash,
                               s.access_expires_at, s.refresh_expires_at, s.revoked_at, u.status,
                               s.replay_nonce, s.replay_ciphertext, s.replay_expires_at
                        FROM user_sessions s
                        JOIN users u ON u.id = s.user_id
                        WHERE s.previous_refresh_token_hash = ?
                          AND s.revoked_at IS NULL
                          AND s.replay_nonce IS NOT NULL
                          AND s.replay_ciphertext IS NOT NULL
                          AND s.replay_expires_at IS NOT NULL
                          AND u.deleted_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, previousRefreshTokenHash)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                RefreshReplayRecord(
                                    session = rs.toSessionRecord(),
                                    nonce = rs.getBytes("replay_nonce"),
                                    ciphertext = rs.getBytes("replay_ciphertext"),
                                    replayExpiresAt = rs.getTimestamp("replay_expires_at").toInstant(),
                                )
                            } else {
                                null
                            }
                        }
                    }
            }
        }

    suspend fun revokeSession(sessionId: UUID): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE user_sessions
                    SET revoked_at = now(),
                        $CLEAR_REFRESH_REPLAY_SQL
                    WHERE id = ?
                      AND revoked_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, sessionId)
                    statement.executeUpdate() > 0
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
