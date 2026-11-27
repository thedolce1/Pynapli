package com.pynapli.server.safety

import com.pynapli.server.db.withConnection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcHiddenProfileRepository(
    private val dataSource: DataSource,
) : HiddenProfileRepository {
    override suspend fun hideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
        hideIdFactory: () -> UUID,
    ): HiddenProfileRecord =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_hidden_profiles (id, hider_user_id, hidden_user_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT (hider_user_id, hidden_user_id) DO UPDATE
                    SET hider_user_id = EXCLUDED.hider_user_id
                    RETURNING id, hider_user_id, hidden_user_id, created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, hideIdFactory())
                    statement.setObject(2, hiderUserId)
                    statement.setObject(3, hiddenUserId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.toHiddenProfileRecord()
                    }
                }
        }

    override suspend fun unhideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    DELETE FROM user_hidden_profiles
                    WHERE hider_user_id = ?
                      AND hidden_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, hiderUserId)
                    statement.setObject(2, hiddenUserId)
                    statement.executeUpdate() > 0
                }
        }

    override suspend fun listHiddenProfiles(hiderUserId: UUID): List<HiddenProfileSummaryRecord> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT hp.hidden_user_id,
                           p.display_name,
                           hp.created_at,
                           photo.media_asset_id AS photo_media_asset_id
                    FROM user_hidden_profiles hp
                    LEFT JOIN profiles p ON p.user_id = hp.hidden_user_id
                    LEFT JOIN users hidden_user ON hidden_user.id = hp.hidden_user_id
                    -- The unhide list avatar (plan G2-4): the first approved profile photo, only while
                    -- the account is active and visible and no block stands either way.
                    LEFT JOIN LATERAL (
                        SELECT mai.media_asset_id
                        FROM media_albums ma
                        JOIN media_album_items mai
                          ON mai.album_id = ma.id
                         AND mai.removed_at IS NULL
                        JOIN media_assets m
                          ON m.id = mai.media_asset_id
                         AND m.owner_user_id = ma.owner_user_id
                         AND m.deleted_at IS NULL
                        WHERE ma.owner_user_id = hp.hidden_user_id
                          AND ma.kind = 'profile'
                          AND ma.visibility_status = 'visible'
                          AND ma.deleted_at IS NULL
                          AND m.media_type = 'image'
                          AND m.processing_status = 'ready'
                          AND m.moderation_status = 'approved'
                          AND m.intended_album_kind = 'profile'
                        ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                        LIMIT 1
                    ) photo
                      ON hidden_user.status = 'active'
                     AND hidden_user.deleted_at IS NULL
                     AND p.visibility_status = 'visible'
                     AND NOT EXISTS (
                         SELECT 1
                         FROM user_blocks b
                         WHERE (b.blocker_user_id = hp.hider_user_id AND b.blocked_user_id = hp.hidden_user_id)
                            OR (b.blocker_user_id = hp.hidden_user_id AND b.blocked_user_id = hp.hider_user_id)
                     )
                    WHERE hp.hider_user_id = ?
                    ORDER BY hp.created_at DESC, hp.hidden_user_id ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, hiderUserId)
                    statement.setInt(2, MAX_HIDDEN_PROFILES_LISTED)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    HiddenProfileSummaryRecord(
                                        hiddenUserId = rs.getObject("hidden_user_id", UUID::class.java),
                                        hiddenDisplayName = rs.getString("display_name"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                        photoMediaAssetId = rs.getObject("photo_media_asset_id", UUID::class.java),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun listHiddenProfileIds(hiderUserId: UUID): Set<UUID> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT hidden_user_id
                    FROM user_hidden_profiles
                    WHERE hider_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, hiderUserId)
                    statement.executeQuery().use { rs ->
                        buildSet {
                            while (rs.next()) {
                                add(rs.getObject("hidden_user_id", UUID::class.java))
                            }
                        }
                    }
                }
        }

    private fun ResultSet.toHiddenProfileRecord(): HiddenProfileRecord =
        HiddenProfileRecord(
            id = getObject("id", UUID::class.java),
            hiderUserId = getObject("hider_user_id", UUID::class.java),
            hiddenUserId = getObject("hidden_user_id", UUID::class.java),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

// The unhide list shows the newest hides up to this many (hides are rare; one page is enough).
internal const val MAX_HIDDEN_PROFILES_LISTED = 500
