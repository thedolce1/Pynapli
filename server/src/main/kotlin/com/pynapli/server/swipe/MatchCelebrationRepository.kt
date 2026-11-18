package com.pynapli.server.swipe

import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A match the viewer has not seen the "It's a match" moment for, with the other person's card bits. */
data class UncelebratedMatchRecord(
    val matchId: UUID,
    val otherUserId: UUID,
    val otherDisplayName: String,
    val otherPhotoMediaAssetId: UUID?,
    val createdAt: Instant,
)

interface MatchCelebrationRepository {
    /** Oldest first, at most [limit], only people the viewer can still see (active, visible, unblocked). */
    suspend fun listUncelebrated(
        viewerUserId: UUID,
        since: Instant,
        limit: Int,
    ): List<UncelebratedMatchRecord>

    /** The other participant of each Psync session in which [viewerUserId] was a together-match target. */
    suspend fun togetherSessionPartners(
        viewerUserId: UUID,
        since: Instant,
    ): List<Set<UUID>>

    suspend fun firstProfilePhoto(userId: UUID): UUID?

    /** Marks the viewer's own matches among [matchIds] seen; returns how many were newly marked. */
    suspend fun markCelebrated(
        viewerUserId: UUID,
        matchIds: List<UUID>,
    ): Int
}

class JdbcMatchCelebrationRepository(
    private val dataSource: DataSource,
) : MatchCelebrationRepository {
    override suspend fun listUncelebrated(
        viewerUserId: UUID,
        since: Instant,
        limit: Int,
    ): List<UncelebratedMatchRecord> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    WITH unseen AS (
                        SELECT m.id AS match_id,
                               m.created_at,
                               CASE WHEN m.user_a = ? THEN m.user_b ELSE m.user_a END AS other_id
                        FROM matches m
                        WHERE (m.user_a = ? OR m.user_b = ?)
                          AND m.created_at >= ?
                          AND NOT EXISTS (
                              SELECT 1 FROM match_celebrations mc
                              WHERE mc.match_id = m.id AND mc.user_id = ?
                          )
                    )
                    SELECT un.match_id,
                           un.created_at,
                           un.other_id,
                           COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                           ($FIRST_PROFILE_PHOTO_SQL) AS photo_id
                    FROM unseen un
                    JOIN users u
                      ON u.id = un.other_id
                     AND u.status = 'active'
                     AND u.deleted_at IS NULL
                    JOIN profiles p
                      ON p.user_id = un.other_id
                     AND p.visibility_status = 'visible'
                    WHERE NOT EXISTS (
                        SELECT 1 FROM user_blocks ub
                        WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = un.other_id)
                           OR (ub.blocker_user_id = un.other_id AND ub.blocked_user_id = ?)
                    )
                      AND NOT EXISTS (
                          SELECT 1 FROM user_hidden_profiles uhp
                          WHERE uhp.hider_user_id = ? AND uhp.hidden_user_id = un.other_id
                      )
                    ORDER BY un.created_at ASC, un.match_id ASC
                    LIMIT ?
                    """.trimIndent().replace("OWNER_ID", "un.other_id"),
                ).use { statement ->
                    statement.setObject(1, viewerUserId)
                    statement.setObject(2, viewerUserId)
                    statement.setObject(3, viewerUserId)
                    statement.setTimestamp(4, java.sql.Timestamp.from(since))
                    statement.setObject(5, viewerUserId)
                    statement.setObject(6, viewerUserId)
                    statement.setObject(7, viewerUserId)
                    statement.setObject(8, viewerUserId)
                    statement.setInt(9, limit)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    UncelebratedMatchRecord(
                                        matchId = rs.getObject("match_id", UUID::class.java),
                                        otherUserId = rs.getObject("other_id", UUID::class.java),
                                        otherDisplayName = rs.getString("display_name"),
                                        otherPhotoMediaAssetId = rs.getObject("photo_id", UUID::class.java),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun togetherSessionPartners(
        viewerUserId: UUID,
        since: Instant,
    ): List<Set<UUID>> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT psm.session_id, pp.user_id
                    FROM psync_session_matches psm
                    JOIN psync_session_participants pp ON pp.session_id = psm.session_id
                    WHERE psm.kind = 'together'
                      AND psm.target_user_id = ?
                      AND psm.created_at >= ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, viewerUserId)
                    statement.setTimestamp(2, java.sql.Timestamp.from(since))
                    statement.executeQuery().use { rs ->
                        val bySession = linkedMapOf<UUID, MutableSet<UUID>>()
                        while (rs.next()) {
                            bySession
                                .getOrPut(rs.getObject("session_id", UUID::class.java)) { linkedSetOf() }
                                .add(rs.getObject("user_id", UUID::class.java))
                        }
                        bySession.values.filter { it.size == 2 }
                    }
                }
        }

    override suspend fun firstProfilePhoto(userId: UUID): UUID? =
        dataSource.withConnection { connection ->
            connection.prepareStatement("SELECT (${FIRST_PROFILE_PHOTO_SQL.replace("OWNER_ID", "?::uuid")})").use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
            }
        }

    override suspend fun markCelebrated(
        viewerUserId: UUID,
        matchIds: List<UUID>,
    ): Int {
        if (matchIds.isEmpty()) return 0
        return dataSource.withConnection { connection -> connection.insertCelebrations(viewerUserId, matchIds) }
    }
}

private fun Connection.insertCelebrations(
    viewerUserId: UUID,
    matchIds: List<UUID>,
): Int =
    prepareStatement(
        """
        INSERT INTO match_celebrations (match_id, user_id)
        SELECT m.id, ?
        FROM matches m
        WHERE m.id = ANY(?::uuid[])
          AND (m.user_a = ? OR m.user_b = ?)
        ON CONFLICT DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.setArray(2, createArrayOf("uuid", matchIds.toTypedArray()))
        statement.setObject(3, viewerUserId)
        statement.setObject(4, viewerUserId)
        statement.executeUpdate()
    }

/** The first approved, ready profile photo of OWNER_ID: the same photo the deck and Grid lead with. */
private const val FIRST_PROFILE_PHOTO_SQL =
    """
    SELECT mai.media_asset_id
    FROM media_albums ma
    JOIN media_album_items mai
      ON mai.album_id = ma.id
     AND mai.removed_at IS NULL
    JOIN media_assets m
      ON m.id = mai.media_asset_id
     AND m.owner_user_id = ma.owner_user_id
     AND m.deleted_at IS NULL
    WHERE ma.owner_user_id = OWNER_ID
      AND ma.kind = 'profile'
      AND ma.visibility_status = 'visible'
      AND ma.deleted_at IS NULL
      AND m.media_type = 'image'
      AND m.processing_status = 'ready'
      AND m.moderation_status = 'approved'
    ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
    LIMIT 1
    """
