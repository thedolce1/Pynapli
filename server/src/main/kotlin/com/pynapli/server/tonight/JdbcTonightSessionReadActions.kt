package com.pynapli.server.tonight

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.isTonightPhotoOwnedBy(
    userId: UUID,
    photoAssetId: UUID,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT 1
                    FROM media_assets
                    WHERE id = ?
                      AND owner_user_id = ?
                      AND deleted_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, photoAssetId)
                    statement.setObject(2, userId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
        }
    }

internal suspend fun DataSource.hasActiveOrProposedTonightSession(
    userId: UUID,
    now: Instant,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection -> connection.hasActiveOrProposedTonightSession(userId, now) }
    }

internal fun Connection.hasActiveOrProposedTonightSession(
    userId: UUID,
    now: Instant,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM tonight_session_participants p
            JOIN tonight_sessions s ON s.id = p.session_id
            WHERE p.user_id = ?
              AND s.status IN ('proposed', 'active')
              AND s.expires_at > ?
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setTimestamp(2, Timestamp.from(now))
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

internal suspend fun DataSource.countTonightSessionsCreatedSince(
    userId: UUID,
    since: Instant,
): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection -> connection.countTonightSessionsCreatedSince(userId, since) }
    }

internal fun Connection.countTonightSessionsCreatedSince(
    userId: UUID,
    since: Instant,
): Int =
    prepareStatement(
        """
        SELECT COUNT(*)
        FROM tonight_sessions
        WHERE created_by_user_id = ?
          AND created_at > ?
          AND status <> 'declined'
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setTimestamp(2, Timestamp.from(since))
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

internal suspend fun DataSource.findActiveTonightLinkedPartner(userId: UUID): UUID? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT CASE WHEN user_id = ? THEN linked_user_id ELSE user_id END AS partner_user_id
                    FROM user_profile_links
                    WHERE status = 'active'
                      AND unlinked_at IS NULL
                      AND (user_id = ? OR linked_user_id = ?)
                    ORDER BY accepted_at DESC NULLS LAST, id ASC
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, userId)
                    statement.setObject(3, userId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.getObject("partner_user_id", UUID::class.java)
                    }
                }
        }
    }

internal suspend fun DataSource.findTonightSessionById(sessionId: UUID): TonightSession? =
    withContext(Dispatchers.IO) {
        connection.use { connection -> connection.loadTonightSession(sessionId) }
    }

internal suspend fun DataSource.findCurrentTonightSessionForUser(
    userId: UUID,
    now: Instant,
): TonightSession? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val sessionId =
                connection
                    .prepareStatement(
                        """
                        SELECT s.id
                        FROM tonight_sessions s
                        JOIN tonight_session_participants p ON p.session_id = s.id
                        WHERE p.user_id = ?
                          AND s.status IN ('proposed', 'active')
                          AND s.expires_at > ?
                        ORDER BY s.created_at DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.setTimestamp(2, Timestamp.from(now))
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) null else rs.getObject("id", UUID::class.java)
                        }
                    } ?: return@withContext null
            connection.loadTonightSession(sessionId, withDisplayNames = true)
        }
    }

internal suspend fun DataSource.expireStaleTonightSessions(now: Instant): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE tonight_sessions
                    SET status = 'expired'
                    WHERE status IN ('proposed', 'active')
                      AND expires_at <= ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.executeUpdate()
                }
        }
    }
