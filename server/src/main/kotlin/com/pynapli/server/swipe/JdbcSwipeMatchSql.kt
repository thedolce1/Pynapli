package com.pynapli.server.swipe

import java.sql.Connection
import java.util.UUID

internal fun Connection.upsertMatch(
    actorUserId: UUID,
    targetUserId: UUID,
    matchIdFactory: () -> UUID
): MatchRecord {
    val (userA, userB) = canonicalPair(actorUserId, targetUserId)
    return prepareStatement(
        """
        INSERT INTO matches (id, user_a, user_b)
        VALUES (?, ?, ?)
        ON CONFLICT (user_a, user_b) DO UPDATE
        SET user_a = EXCLUDED.user_a
        RETURNING id, user_a, user_b, created_at
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, matchIdFactory())
        statement.setObject(2, userA)
        statement.setObject(3, userB)
        statement.executeQuery().use { rs ->
            rs.next()
            MatchRecord(
                matchId = rs.getObject("id", UUID::class.java),
                userA = rs.getObject("user_a", UUID::class.java),
                userB = rs.getObject("user_b", UUID::class.java),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}

internal fun Connection.listMatchSummaries(viewerUserId: UUID, limit: Int): List<MatchSummaryRecord> =
    prepareStatement(
        """
        SELECT m.id AS match_id,
               matched_user.id AS matched_user_id,
               m.created_at,
               p.display_name,
               profile_photo.media_asset_id AS thumbnail_media_asset_id
        FROM matches m
        JOIN users matched_user
          ON matched_user.id = CASE WHEN m.user_a = ? THEN m.user_b ELSE m.user_a END
         AND matched_user.status = 'active'
         AND matched_user.deleted_at IS NULL
        JOIN profiles p
          ON p.user_id = matched_user.id
         AND p.visibility_status = 'visible'
        LEFT JOIN account_risk_states matched_risk
          ON matched_risk.user_id = matched_user.id
        LEFT JOIN LATERAL (
            SELECT mai.media_asset_id
            FROM media_albums ma
            JOIN media_album_items mai
              ON mai.album_id = ma.id
             AND mai.removed_at IS NULL
            JOIN media_assets ma2
              ON ma2.id = mai.media_asset_id
             AND ma2.owner_user_id = ma.owner_user_id
             AND ma2.deleted_at IS NULL
            WHERE ma.owner_user_id = matched_user.id
              AND ma.kind = 'profile'
              AND ma.visibility_status = 'visible'
              AND ma.deleted_at IS NULL
              AND ma2.media_type = 'image'
              AND ma2.processing_status = 'ready'
              AND ma2.moderation_status = 'approved'
            ORDER BY mai.sort_order ASC, mai.added_at DESC, ma2.id ASC
            LIMIT 1
        ) profile_photo ON true
        WHERE (m.user_a = ? OR m.user_b = ?)
          AND COALESCE(matched_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
        ORDER BY m.created_at DESC, m.id ASC
        LIMIT ?
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, viewerUserId)
        statement.setInt(4, limit)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        MatchSummaryRecord(
                            matchId = rs.getObject("match_id", UUID::class.java),
                            matchedUserId = rs.getObject("matched_user_id", UUID::class.java),
                            matchedDisplayName = rs.getString("display_name"),
                            matchedThumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", UUID::class.java),
                            createdAt = rs.getTimestamp("created_at").toInstant()
                        )
                    )
                }
            }
        }
    }

internal fun canonicalPair(a: UUID, b: UUID): Pair<UUID, UUID> =
    if (a.toString() < b.toString()) a to b else b to a
