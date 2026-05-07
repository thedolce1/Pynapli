package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class JdbcSwipeRepository(
    private val dataSource: DataSource
) : SwipeRepository {
    override suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters
    ): SwipeDeckResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val viewerHasLocation = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM user_locations WHERE user_id = ?)"
            ).use { statement ->
                statement.setObject(1, viewerUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }

            if (!viewerHasLocation) {
                return@withContext SwipeDeckResult(viewerHadLocation = false, cards = emptyList())
            }

            val cards = connection.prepareStatement(
                """
                WITH viewer_location AS (
                    SELECT geom
                    FROM user_locations
                    WHERE user_id = ?
                )
                SELECT u.id AS user_id,
                       COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                       p.display_age,
                       profile_photo.media_asset_id AS thumbnail_media_asset_id,
                       linked_partner.linked_partner_user_id,
                       linked_partner.linked_partner_display_name,
                       linked_partner.linked_partner_thumbnail_media_asset_id,
                       ds.show_distance,
                       ST_Distance(v.geom, ul.geom) AS distance_meters
                FROM viewer_location v
                JOIN user_locations ul ON true
                JOIN users u ON u.id = ul.user_id
                JOIN profiles p ON p.user_id = u.id
                JOIN discovery_settings ds ON ds.user_id = u.id
                LEFT JOIN account_risk_states ars ON ars.user_id = u.id
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
                    WHERE ma.owner_user_id = u.id
                      AND ma.kind = 'profile'
                      AND ma.visibility_status = 'visible'
                      AND ma.deleted_at IS NULL
                      AND m.media_type = 'image'
                      AND m.processing_status = 'ready'
                      AND m.moderation_status = 'approved'
                    ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                    LIMIT 1
                ) profile_photo ON true
                LEFT JOIN LATERAL (
                    SELECT upl.linked_user_id AS linked_partner_user_id,
                           COALESCE(NULLIF(linked_profile.display_name, ''), 'Pynapli User') AS linked_partner_display_name,
                           linked_partner_photo.media_asset_id AS linked_partner_thumbnail_media_asset_id
                    FROM user_profile_links upl
                    JOIN users linked_user
                      ON linked_user.id = upl.linked_user_id
                    JOIN profiles linked_profile
                      ON linked_profile.user_id = linked_user.id
                    JOIN discovery_settings linked_ds
                      ON linked_ds.user_id = linked_user.id
                    LEFT JOIN account_risk_states linked_risk
                      ON linked_risk.user_id = linked_user.id
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
                        WHERE ma.owner_user_id = linked_user.id
                          AND ma.kind = 'profile'
                          AND ma.visibility_status = 'visible'
                          AND ma.deleted_at IS NULL
                          AND m.media_type = 'image'
                          AND m.processing_status = 'ready'
                          AND m.moderation_status = 'approved'
                        ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                        LIMIT 1
                    ) linked_partner_photo ON true
                    WHERE upl.user_id = u.id
                      AND upl.status = 'active'
                      AND upl.unlinked_at IS NULL
                      AND linked_user.status = 'active'
                      AND linked_user.deleted_at IS NULL
                      AND linked_profile.visibility_status = 'visible'
                      AND linked_ds.incognito = false
                      AND COALESCE(linked_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                      AND NOT EXISTS (
                          SELECT 1
                          FROM user_blocks ub
                          WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = linked_user.id)
                             OR (ub.blocker_user_id = linked_user.id AND ub.blocked_user_id = ?)
                      )
                    ORDER BY upl.accepted_at DESC NULLS LAST, upl.created_at DESC, upl.id ASC
                    LIMIT 1
                ) linked_partner ON true
                WHERE u.id <> ?
                  AND u.status = 'active'
                  AND u.deleted_at IS NULL
                  AND p.visibility_status = 'visible'
                  AND ds.swipe_visible = true
                  AND ds.incognito = false
                  AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  AND ST_DWithin(v.geom, ul.geom, ?)
                  AND (?::integer IS NULL OR p.display_age >= ?::integer)
                  AND (?::integer IS NULL OR p.display_age <= ?::integer)
                  AND (cardinality(?::text[]) = 0 OR lower(p.relationship_context) = ANY(?::text[]))
                  AND (
                      ?::boolean IS NULL
                      OR (?::boolean = true AND linked_partner.linked_partner_user_id IS NOT NULL)
                      OR (?::boolean = false AND linked_partner.linked_partner_user_id IS NULL)
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM swipe_actions sa
                      WHERE sa.actor_user_id = ?
                        AND sa.target_user_id = u.id
                  )
                ORDER BY ST_Distance(v.geom, ul.geom) ASC, u.id ASC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                val contexts = connection.createArrayOf("text", filters.relationshipContexts.toTypedArray())
                statement.setObject(1, viewerUserId)
                statement.setObject(2, viewerUserId)
                statement.setObject(3, viewerUserId)
                statement.setObject(4, viewerUserId)
                statement.setDouble(5, radiusMeters.toDouble())
                statement.setObject(6, filters.minimumAge)
                statement.setObject(7, filters.minimumAge)
                statement.setObject(8, filters.maximumAge)
                statement.setObject(9, filters.maximumAge)
                statement.setArray(10, contexts)
                statement.setArray(11, contexts)
                statement.setObject(12, filters.linkedOnly)
                statement.setObject(13, filters.linkedOnly)
                statement.setObject(14, filters.linkedOnly)
                statement.setObject(15, viewerUserId)
                statement.setInt(16, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                SwipeCardRecord(
                                    userId = rs.getObject("user_id", UUID::class.java),
                                    displayName = rs.getString("display_name"),
                                    displayAge = rs.getInt("display_age").takeUnless { rs.wasNull() },
                                    thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", UUID::class.java),
                                    showDistance = rs.getBoolean("show_distance"),
                                    distanceMeters = rs.getDouble("distance_meters"),
                                    linkedPartnerUserId = rs.getObject("linked_partner_user_id", UUID::class.java),
                                    linkedPartnerDisplayName = rs.getString("linked_partner_display_name"),
                                    linkedPartnerThumbnailMediaAssetId = rs.getObject(
                                        "linked_partner_thumbnail_media_asset_id",
                                        UUID::class.java
                                    )
                                )
                            )
                        }
                    }
                }
            }

            SwipeDeckResult(viewerHadLocation = true, cards = cards)
        }
    }

    override suspend fun isActionTargetEligible(
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (viewerUserId == targetUserId) return@withContext false
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                WITH viewer_location AS (
                    SELECT geom
                    FROM user_locations
                    WHERE user_id = ?
                )
                SELECT EXISTS (
                    SELECT 1
                    FROM viewer_location v
                    JOIN user_locations target_location
                      ON target_location.user_id = ?
                    JOIN users target
                      ON target.id = target_location.user_id
                    JOIN profiles target_profile
                      ON target_profile.user_id = target.id
                    JOIN discovery_settings target_settings
                      ON target_settings.user_id = target.id
                    LEFT JOIN account_risk_states target_risk
                      ON target_risk.user_id = target.id
                    WHERE target.id = ?
                      AND target.status = 'active'
                      AND target.deleted_at IS NULL
                      AND target_profile.visibility_status = 'visible'
                      AND target_settings.swipe_visible = true
                      AND target_settings.incognito = false
                      AND (?::boolean = false OR target_settings.allow_intro_notes = true)
                      AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                      AND ST_DWithin(v.geom, target_location.geom, ?)
                      AND NOT EXISTS (
                          SELECT 1 FROM swipe_actions sa
                          WHERE sa.actor_user_id = ?
                            AND sa.target_user_id = target.id
                      )
                )
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, viewerUserId)
                statement.setObject(2, targetUserId)
                statement.setObject(3, targetUserId)
                statement.setBoolean(4, requireIntroNotesAllowed)
                statement.setDouble(5, radiusMeters.toDouble())
                statement.setObject(6, viewerUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }
    }

    override suspend fun recordSwipeAction(
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        noteText: String?,
        actionIdFactory: () -> UUID,
        matchIdFactory: () -> UUID
    ): SwipeActionResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action, note_text)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (actor_user_id, target_user_id) DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, actionIdFactory())
                    statement.setObject(2, actorUserId)
                    statement.setObject(3, targetUserId)
                    statement.setString(4, action)
                    statement.setString(5, noteText)
                    if (statement.executeUpdate() == 0) {
                        connection.rollback()
                        return@withContext SwipeActionResult(recorded = false, match = null)
                    }
                }

                if (action == "pineapple_like") {
                    connection.recordPineappleLikeActivity(actorUserId, targetUserId)
                }

                val match: MatchRecord? = if (action in POSITIVE_ACTIONS) {
                    val targetLikedActor = connection.prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1 FROM swipe_actions
                            WHERE actor_user_id = ?
                              AND target_user_id = ?
                              AND action IN ('like', 'pineapple_like')
                        )
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, targetUserId)
                        statement.setObject(2, actorUserId)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getBoolean(1)
                        }
                    }

                    if (targetLikedActor) {
                        connection.upsertMatch(actorUserId, targetUserId, matchIdFactory)
                    } else {
                        null
                    }
                } else {
                    null
                }

                connection.commit()
                SwipeActionResult(recorded = true, match = match)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun listMatches(viewerUserId: UUID, limit: Int): List<MatchSummaryRecord> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
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
        }
    }

    private fun Connection.recordPineappleLikeActivity(actorUserId: UUID, targetUserId: UUID) {
        prepareStatement(
            """
            INSERT INTO activity_events (
                id,
                recipient_user_id,
                actor_user_id,
                event_type,
                target_user_id,
                aggregate_count,
                first_occurred_at,
                last_counted_at,
                occurred_at,
                updated_at
            )
            VALUES (?, ?, ?, 'pineapple_like', ?, 1, now(), now(), now(), now())
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, targetUserId)
            statement.setObject(3, actorUserId)
            statement.setObject(4, targetUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.upsertMatch(
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

    companion object {
        private val POSITIVE_ACTIONS = setOf("like", "pineapple_like")
    }
}

internal fun canonicalPair(a: UUID, b: UUID): Pair<UUID, UUID> =
    if (a.compareTo(b) < 0) a to b else b to a
