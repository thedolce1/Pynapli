package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.profiling.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcSwipeRepository(
    private val dataSource: DataSource
) : SwipeRepository {
    override suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>
    ): SwipeDeckResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val viewerHasLocation = profile("swipe.deck.repo.viewerLocation") {
                connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM user_locations WHERE user_id = ?)"
                ).use { statement ->
                    statement.setObject(1, viewerUserId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
            }

            if (!viewerHasLocation) {
                return@withContext SwipeDeckResult(viewerHadLocation = false, cards = emptyList())
            }

            // Two-phase deck query (mirrors the Grid pattern from IMPLEMENTATION_AUDIT L70):
            //   Phase 1 ranks candidates with the cheap row-level filters and base profile
            //   columns only. The photo carousel and linked-partner mini hydration LATERALs
            //   are deferred to Phase 2 so we evaluate them for the LIMIT N rows we will
            //   actually return, not every candidate inside ST_DWithin.
            val rankedRows = profile("swipe.deck.repo.rank") {
                connection.queryDeckRank(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = limit,
                    filters = filters,
                    excludedTargetUserIds = excludedTargetUserIds
                )
            }

            if (rankedRows.isEmpty()) {
                return@withContext SwipeDeckResult(viewerHadLocation = true, cards = emptyList())
            }

            val hydration = profile("swipe.deck.repo.hydrate") {
                connection.hydrateDeckCards(
                    viewerUserId = viewerUserId,
                    userIds = rankedRows.map { it.userId }
                )
            }

            val cards = rankedRows.map { row ->
                val h = hydration[row.userId]
                SwipeCardRecord(
                    userId = row.userId,
                    displayName = row.displayName,
                    displayAge = row.displayAge,
                    thumbnailMediaAssetId = h?.thumbnailMediaAssetId,
                    showDistance = row.showDistance,
                    distanceMeters = row.distanceMeters,
                    linkedPartnerUserId = h?.linkedPartnerUserId,
                    linkedPartnerDisplayName = h?.linkedPartnerDisplayName,
                    linkedPartnerThumbnailMediaAssetId = h?.linkedPartnerThumbnailMediaAssetId,
                    bio = row.bio,
                    tags = row.tags,
                    thumbnailMediaAssetIds = h?.thumbnailMediaAssetIds.orEmpty(),
                    verified = row.verified,
                    visiting = row.visiting
                )
            }

            SwipeDeckResult(viewerHadLocation = true, cards = cards)
        }
    }

    private fun Connection.queryDeckRank(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>
    ): List<DeckRankRow> {
        // KNN spatial ordering via PostGIS `<->` operator. The GiST index on
        // user_locations.geom can serve the ordering directly when `<->` is the
        // sole ORDER BY clause — adding a CASE expression or a tiebreaker
        // forces a full sort and defeats the optimization. So:
        //   - `is_boosted` is returned as a SELECT column (cheap EXISTS) rather
        //     than living in the ORDER BY; the Kotlin side re-shuffles boosted
        //     users to the head of the page (spec L320: boost increases
        //     impressions, not matches, so prioritizing within the local page
        //     is sufficient).
        //   - The previous `u.id ASC` tiebreaker is dropped; ST_Distance ties
        //     on real coordinates are vanishingly rare and the tiebreaker
        //     defeats the KNN index scan.
        val sql = """
            WITH viewer_location AS (
                -- Passport/travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354): an
                -- active travel_locations row overrides GPS for deck ranking.
                SELECT COALESCE(tl.geom, ul.geom) AS geom
                FROM user_locations ul
                LEFT JOIN travel_locations tl
                  ON tl.user_id = ul.user_id
                 AND tl.status = 'active'
                 AND tl.active_until IS NULL
                WHERE ul.user_id = ?
            )
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                   p.display_age,
                   p.bio,
                   COALESCE(p.lifestyle_tags, '{}'::text[]) || COALESCE(p.logistics_tags, '{}'::text[]) AS profile_tags,
                   p.verified_at IS NOT NULL AS verified,
                   ds.show_distance,
                   -- Distance shown to the viewer reflects where the target *appears*
                   -- to be — their travel point if active, else their real GPS.
                   ST_Distance(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters,
                   EXISTS (
                       SELECT 1
                       FROM boost_activations ba
                       WHERE ba.user_id = u.id
                         AND ba.status = 'active'
                         AND ba.ends_at > now()
                   ) AS is_boosted,
                   -- SWIPE_IMPLEMENTATION_SPEC L348: target is "visiting" when
                   -- they have an active travel_locations row. The LEFT JOIN
                   -- below brings in `target_travel.id` only for rows in that
                   -- state — null means physically local.
                   target_travel.id IS NOT NULL AS visiting,
                   -- FILTERS_SPEC L406-417 ranking inputs (B4 v1):
                   -- +1 when verifiedPreferred is on AND the target is verified.
                   -- Intent/style scoring is deferred; they would lose signal on the
                   -- B3 relaxed pass (the relaxed filter set drops intents/styles, so
                   -- a CASE that piggybacks on the filter param would always score 0).
                   -- A v2 would carry separate "ranking hints" alongside filters.
                   (CASE WHEN ?::boolean AND p.verified_at IS NOT NULL THEN 1 ELSE 0 END) AS soft_score
            FROM viewer_location v
            JOIN user_locations ul ON true
            JOIN users u ON u.id = ul.user_id
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            LEFT JOIN account_risk_states ars ON ars.user_id = u.id
            -- SWIPE_IMPLEMENTATION_SPEC L344-348: target-side travel override.
            -- When a target has an active travel_locations row they appear in
            -- decks centered on that destination (matches Tinder Passport's
            -- "place yourself elsewhere" semantics), and the visiting flag fires.
            LEFT JOIN travel_locations target_travel
              ON target_travel.user_id = u.id
             AND target_travel.status = 'active'
             AND target_travel.active_until IS NULL
            WHERE u.id <> ?
              AND u.status = 'active'
              AND u.deleted_at IS NULL
              AND p.visibility_status = 'visible'
              AND ds.swipe_visible = true
              AND ds.incognito = false
              AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
              -- OR keeps both ST_DWithin calls index-eligible. The first uses
              -- the GiST index on user_locations.geom for the bulk of (non-
              -- travelling) candidates; the second uses the GiST on
              -- travel_locations.geom for the (much smaller) active-travel set.
              AND (
                  ST_DWithin(v.geom, ul.geom, ?)
                  OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))
              )
              AND (?::integer IS NULL OR p.display_age >= ?::integer)
              AND (?::integer IS NULL OR p.display_age <= ?::integer)
              AND (cardinality(?::text[]) = 0 OR lower(p.relationship_context) = ANY(?::text[]))
              AND (?::boolean = false OR p.verified_at IS NOT NULL)
              AND (?::boolean = false OR p.face_photo_verified_at IS NOT NULL)
              AND (?::boolean = false OR p.age_verified_at IS NOT NULL)
              AND (cardinality(?::text[]) = 0 OR p.discovery_style = ANY(?::text[]))
              AND (cardinality(?::text[]) = 0 OR (p.intents IS NOT NULL AND p.intents && ?::text[]))
              AND (cardinality(?::text[]) = 0 OR (p.lifestyle_tags IS NOT NULL AND p.lifestyle_tags && ?::text[]))
              AND (cardinality(?::text[]) = 0 OR (p.logistics_tags IS NOT NULL AND p.logistics_tags && ?::text[]))
              AND (
                  ?::boolean IS NULL
                  OR ?::boolean = EXISTS (
                      SELECT 1
                      FROM user_profile_links upl
                      JOIN users linked_user
                        ON linked_user.id = upl.linked_user_id
                      JOIN profiles linked_profile
                        ON linked_profile.user_id = linked_user.id
                      JOIN discovery_settings linked_ds
                        ON linked_ds.user_id = linked_user.id
                      LEFT JOIN account_risk_states linked_risk
                        ON linked_risk.user_id = linked_user.id
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
                  )
              )
              AND NOT EXISTS (
                  SELECT 1 FROM swipe_actions sa
                  WHERE sa.actor_user_id = ?
                    AND sa.target_user_id = u.id
                    AND sa.rewound_at IS NULL
              )
              AND (
                  cardinality(?::uuid[]) = 0
                  OR u.id <> ALL(?::uuid[])
              )
            -- KNN ORDER on a COALESCE'd expression can't use the GiST index
            -- directly, so we accept a small in-memory sort over the (already
            -- filtered) candidate set. The candidate set is bounded by the
            -- ST_DWithin filter above, which IS index-driven.
            ORDER BY soft_score DESC, v.geom <-> COALESCE(target_travel.geom, ul.geom)
            LIMIT ?
        """.trimIndent()

        val rankedByDistance = prepareStatement(sql).use { statement ->
            val contexts = createArrayOf("text", filters.relationshipContexts.toTypedArray())
            val styles = createArrayOf("text", filters.discoveryStyles.toTypedArray())
            val intents = createArrayOf("text", filters.intents.toTypedArray())
            val lifestyleTags = createArrayOf("text", filters.lifestyleTags.toTypedArray())
            val logisticsTags = createArrayOf("text", filters.logisticsTags.toTypedArray())
            val excludedIds = createArrayOf("uuid", excludedTargetUserIds.toTypedArray())
            statement.setObject(1, viewerUserId)
            // $2: verifiedPreferred soft_score signal (SELECT). Placed early in the SQL
            // text so the SELECT param appears before the WHERE/ORDER BY params.
            statement.setBoolean(2, filters.verifiedPreferred)
            statement.setObject(3, viewerUserId)
            // $4 + $5: ST_DWithin radius bound twice — once against target's
            // real GPS, once against target's travel point.
            statement.setDouble(4, radiusMeters.toDouble())
            statement.setDouble(5, radiusMeters.toDouble())
            statement.setObject(6, filters.minimumAge)
            statement.setObject(7, filters.minimumAge)
            statement.setObject(8, filters.maximumAge)
            statement.setObject(9, filters.maximumAge)
            statement.setArray(10, contexts)
            statement.setArray(11, contexts)
            statement.setBoolean(12, filters.verifiedOnly)
            statement.setBoolean(13, filters.facePhotoVerifiedOnly)
            statement.setBoolean(14, filters.ageVerifiedOnly)
            statement.setArray(15, styles)
            statement.setArray(16, styles)
            statement.setArray(17, intents)
            statement.setArray(18, intents)
            statement.setArray(19, lifestyleTags)
            statement.setArray(20, lifestyleTags)
            statement.setArray(21, logisticsTags)
            statement.setArray(22, logisticsTags)
            statement.setObject(23, filters.linkedOnly)
            statement.setObject(24, filters.linkedOnly)
            statement.setObject(25, viewerUserId)
            statement.setObject(26, viewerUserId)
            statement.setObject(27, viewerUserId)
            statement.setArray(28, excludedIds)
            statement.setArray(29, excludedIds)
            statement.setInt(30, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DeckRankRow(
                                userId = rs.getObject("user_id", UUID::class.java),
                                displayName = rs.getString("display_name"),
                                displayAge = rs.getInt("display_age").takeUnless { rs.wasNull() },
                                bio = rs.getString("bio"),
                                tags = rs.readStringArray("profile_tags"),
                                verified = rs.getBoolean("verified"),
                                visiting = rs.getBoolean("visiting"),
                                showDistance = rs.getBoolean("show_distance"),
                                distanceMeters = rs.getDouble("distance_meters"),
                                isBoosted = rs.getBoolean("is_boosted"),
                                softScore = rs.getInt("soft_score")
                            )
                        )
                    }
                }
            }
        }

        // Boosted users float to the head of the page, preserving distance order
        // within each bucket. Stable sort: boosted=true first, then SQL order.
        return rankedByDistance.sortedByDescending { it.isBoosted }
    }

    private fun Connection.hydrateDeckCards(
        viewerUserId: UUID,
        userIds: List<UUID>
    ): Map<UUID, DeckHydration> {
        if (userIds.isEmpty()) return emptyMap()
        val sql = """
            SELECT u.id AS user_id,
                   profile_photos.thumbnail_media_asset_id,
                   profile_photos.media_asset_ids AS profile_photo_ids,
                   linked_partner.linked_partner_user_id,
                   linked_partner.linked_partner_display_name,
                   linked_partner.linked_partner_thumbnail_media_asset_id
            FROM users u
            LEFT JOIN LATERAL (
                SELECT (array_agg(media_asset_id ORDER BY rn))[1] AS thumbnail_media_asset_id,
                       array_agg(media_asset_id ORDER BY rn) AS media_asset_ids
                FROM (
                    SELECT mai.media_asset_id,
                           ROW_NUMBER() OVER (
                               ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                           ) AS rn
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
                ) ranked
                WHERE rn <= 5
            ) profile_photos ON true
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
            WHERE u.id = ANY(?::uuid[])
        """.trimIndent()

        return prepareStatement(sql).use { statement ->
            val ids = createArrayOf("uuid", userIds.toTypedArray())
            statement.setObject(1, viewerUserId)
            statement.setObject(2, viewerUserId)
            statement.setArray(3, ids)
            statement.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val userId = rs.getObject("user_id", UUID::class.java)
                        put(
                            userId,
                            DeckHydration(
                                thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", UUID::class.java),
                                thumbnailMediaAssetIds = rs.readUuidArray("profile_photo_ids"),
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
    }

    private data class DeckRankRow(
        val userId: UUID,
        val displayName: String,
        val displayAge: Int?,
        val bio: String?,
        val tags: List<String>,
        val verified: Boolean,
        val visiting: Boolean,
        val showDistance: Boolean,
        val distanceMeters: Double,
        val isBoosted: Boolean,
        val softScore: Int
    )

    private data class DeckHydration(
        val thumbnailMediaAssetId: UUID?,
        val thumbnailMediaAssetIds: List<UUID>,
        val linkedPartnerUserId: UUID?,
        val linkedPartnerDisplayName: String?,
        val linkedPartnerThumbnailMediaAssetId: UUID?
    )

    override suspend fun upsertDeckSession(
        session: SwipeDeckSessionRecord,
        cards: List<SwipeDeckSessionCardRecord>
    ): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.insertDeckSession(session)
                connection.insertDeckCards(cards)

                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override suspend fun findDeckSession(sessionId: UUID, viewerUserId: UUID): SwipeDeckSessionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id,
                           deck_session_token,
                           deck_session_token_hash,
                           viewer_user_id,
                           scope_hash,
                           radius_meters,
                           page_limit,
                           filter_payload::text AS filter_payload,
                           generated_at,
                           expires_at
                    FROM swipe_deck_sessions
                    WHERE id = ?
                      AND viewer_user_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, sessionId)
                    statement.setObject(2, viewerUserId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.toSwipeDeckSessionRecord()
                    }
                }
            }
        }

    override suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT target_user_id
                FROM swipe_deck_cards
                WHERE deck_session_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { rs ->
                    buildSet {
                        while (rs.next()) {
                            add(rs.getObject("target_user_id", UUID::class.java))
                        }
                    }
                }
            }
        }
    }

    override suspend fun isDeckCardActionable(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM swipe_deck_sessions s
                    JOIN swipe_deck_cards c
                      ON c.deck_session_id = s.id
                    WHERE s.id = ?
                      AND s.viewer_user_id = ?
                      AND c.viewer_user_id = ?
                      AND c.target_user_id = ?
                      AND c.card_token_hash = ?
                      AND s.expires_at > ?
                      AND c.action_recorded_at IS NULL
                )
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, viewerUserId)
                statement.setObject(3, viewerUserId)
                statement.setObject(4, targetUserId)
                statement.setString(5, cardTokenHash)
                statement.setTimestamp(6, Timestamp.from(now))
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }
    }

    override suspend fun markDeckCardActionRecorded(
        sessionId: UUID,
        cardTokenHash: String,
        targetUserId: UUID,
        action: String,
        now: Instant
    ): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE swipe_deck_cards
                SET action = ?,
                    action_recorded_at = ?
                WHERE deck_session_id = ?
                  AND target_user_id = ?
                  AND card_token_hash = ?
                  AND action_recorded_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, action)
                statement.setTimestamp(2, Timestamp.from(now))
                statement.setObject(3, sessionId)
                statement.setObject(4, targetUserId)
                statement.setString(5, cardTokenHash)
                statement.executeUpdate()
            }
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
                    -- Travel-mode override for viewer side; targets are still
                    -- ranged against their own real GPS row.
                    SELECT COALESCE(tl.geom, ul.geom) AS geom
                    FROM user_locations ul
                    LEFT JOIN travel_locations tl
                      ON tl.user_id = ul.user_id
                     AND tl.status = 'active'
                     AND tl.active_until IS NULL
                    WHERE ul.user_id = ?
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
                            AND sa.rewound_at IS NULL
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

    override suspend fun checkActionPreconditions(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean,
        now: Instant
    ): SwipeActionPreconditionCheck = withContext(Dispatchers.IO) {
        if (viewerUserId == targetUserId) {
            return@withContext SwipeActionPreconditionCheck(deckCardActionable = false, targetEligible = false)
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                WITH viewer_location AS (
                    -- Travel-mode override for the action-precondition radius
                    -- check; if the viewer Liked someone in their travel city
                    -- then jumped home, this query still uses the travel point
                    -- so the action remains within-radius for the original deck.
                    SELECT COALESCE(tl.geom, ul.geom) AS geom
                    FROM user_locations ul
                    LEFT JOIN travel_locations tl
                      ON tl.user_id = ul.user_id
                     AND tl.status = 'active'
                     AND tl.active_until IS NULL
                    WHERE ul.user_id = ?
                )
                SELECT
                    EXISTS (
                        SELECT 1
                        FROM swipe_deck_sessions s
                        JOIN swipe_deck_cards c
                          ON c.deck_session_id = s.id
                        WHERE s.id = ?
                          AND s.viewer_user_id = ?
                          AND c.viewer_user_id = ?
                          AND c.target_user_id = ?
                          AND c.card_token_hash = ?
                          AND s.expires_at > ?
                          AND c.action_recorded_at IS NULL
                    ) AS deck_card_actionable,
                    EXISTS (
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
                                AND sa.rewound_at IS NULL
                          )
                    ) AS target_eligible
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, viewerUserId)
                statement.setObject(2, sessionId)
                statement.setObject(3, viewerUserId)
                statement.setObject(4, viewerUserId)
                statement.setObject(5, targetUserId)
                statement.setString(6, cardTokenHash)
                statement.setTimestamp(7, Timestamp.from(now))
                statement.setObject(8, targetUserId)
                statement.setObject(9, targetUserId)
                statement.setBoolean(10, requireIntroNotesAllowed)
                statement.setDouble(11, radiusMeters.toDouble())
                statement.setObject(12, viewerUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    SwipeActionPreconditionCheck(
                        deckCardActionable = rs.getBoolean("deck_card_actionable"),
                        targetEligible = rs.getBoolean("target_eligible")
                    )
                }
            }
        }
    }

    override suspend fun findSwipeActionById(actionId: UUID, actorUserId: UUID): SwipeActionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findSwipeActionById(actionId, actorUserId)
            }
        }

    override suspend fun findLatestRewindableAction(
        viewerUserId: UUID,
        radiusMeters: Int
    ): SwipeRewindCandidateRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                WITH viewer_location AS (
                    -- Travel-mode override for rewindable-action discovery; an
                    -- action recorded in travel mode is still rewindable while
                    -- the user is in that travel city.
                    SELECT COALESCE(tl.geom, ul.geom) AS geom
                    FROM user_locations ul
                    LEFT JOIN travel_locations tl
                      ON tl.user_id = ul.user_id
                     AND tl.status = 'active'
                     AND tl.active_until IS NULL
                    WHERE ul.user_id = ?
                )
                SELECT sa.id AS action_id,
                       sa.target_user_id,
                       sa.action,
                       sa.consumed_inventory_id,
                       COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                       p.display_age,
                       p.bio,
                       COALESCE(p.lifestyle_tags, '{}'::text[]) || COALESCE(p.logistics_tags, '{}'::text[]) AS profile_tags,
                       p.verified_at IS NOT NULL AS verified,
                       EXISTS (
                           SELECT 1 FROM travel_locations tlt
                           WHERE tlt.user_id = u.id
                             AND tlt.status = 'active'
                             AND tlt.active_until IS NULL
                       ) AS visiting,
                       profile_photos.thumbnail_media_asset_id,
                       profile_photos.media_asset_ids AS profile_photo_ids,
                       linked_partner.linked_partner_user_id,
                       linked_partner.linked_partner_display_name,
                       linked_partner.linked_partner_thumbnail_media_asset_id,
                       ds.show_distance,
                       ST_Distance(v.geom, ul.geom) AS distance_meters
                FROM swipe_actions sa
                JOIN viewer_location v ON true
                JOIN users u
                  ON u.id = sa.target_user_id
                JOIN user_locations ul
                  ON ul.user_id = u.id
                JOIN profiles p
                  ON p.user_id = u.id
                JOIN discovery_settings ds
                  ON ds.user_id = u.id
                LEFT JOIN account_risk_states ars
                  ON ars.user_id = u.id
                LEFT JOIN LATERAL (
                    SELECT (array_agg(media_asset_id ORDER BY rn))[1] AS thumbnail_media_asset_id,
                           array_agg(media_asset_id ORDER BY rn) AS media_asset_ids
                    FROM (
                        SELECT mai.media_asset_id,
                               ROW_NUMBER() OVER (
                                   ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
                               ) AS rn
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
                    ) ranked
                    WHERE rn <= 5
                ) profile_photos ON true
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
                WHERE sa.actor_user_id = ?
                  AND sa.rewound_at IS NULL
                  AND sa.action IN ('like', 'pass', 'pineapple_like')
                  AND u.status = 'active'
                  AND u.deleted_at IS NULL
                  AND p.visibility_status = 'visible'
                  AND ds.swipe_visible = true
                  AND ds.incognito = false
                  AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
                  AND ST_DWithin(v.geom, ul.geom, ?)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM user_blocks ub
                      WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = u.id)
                         OR (ub.blocker_user_id = u.id AND ub.blocked_user_id = ?)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM matches m
                      WHERE (m.user_a = sa.actor_user_id AND m.user_b = sa.target_user_id)
                         OR (m.user_a = sa.target_user_id AND m.user_b = sa.actor_user_id)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM safety_reports sr
                      WHERE sr.reporter_user_id = sa.actor_user_id
                        AND sr.reported_user_id = sa.target_user_id
                  )
                ORDER BY sa.created_at DESC, sa.id DESC
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, viewerUserId)
                statement.setObject(2, viewerUserId)
                statement.setObject(3, viewerUserId)
                statement.setObject(4, viewerUserId)
                statement.setDouble(5, radiusMeters.toDouble())
                statement.setObject(6, viewerUserId)
                statement.setObject(7, viewerUserId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        SwipeRewindCandidateRecord(
                            actionId = rs.getObject("action_id", UUID::class.java),
                            targetUserId = rs.getObject("target_user_id", UUID::class.java),
                            action = rs.getString("action"),
                            consumedInventoryId = rs.getObject("consumed_inventory_id", UUID::class.java),
                            card = SwipeCardRecord(
                                userId = rs.getObject("target_user_id", UUID::class.java),
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
                                ),
                                bio = rs.getString("bio"),
                                tags = rs.readStringArray("profile_tags"),
                                verified = rs.getBoolean("verified"),
                                visiting = rs.getBoolean("visiting")
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun recordSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        noteText: String?,
        consumedInventoryId: UUID?,
        travelLocationId: UUID?,
        matchIdFactory: () -> UUID
    ): SwipeActionResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO swipe_actions (
                        id, actor_user_id, target_user_id, action,
                        note_text, consumed_inventory_id, travel_location_id
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, actionId)
                    statement.setObject(2, actorUserId)
                    statement.setObject(3, targetUserId)
                    statement.setString(4, action)
                    statement.setString(5, noteText)
                    statement.setObject(6, consumedInventoryId)
                    statement.setObject(7, travelLocationId)
                    if (statement.executeUpdate() == 0) {
                        val existing = connection.findSwipeActionById(actionId, actorUserId)
                        connection.rollback()
                        return@withContext SwipeActionResult(recorded = existing != null, match = existing?.match)
                    }
                }

                if (action == "pineapple_like") {
                    connection.recordPineappleLikeActivity(actorUserId, targetUserId)
                }

                val match: MatchRecord? = if (action in POSITIVE_ACTIONS) {
                    val targetLikedActor = connection.prepareStatement(
                        """
                        -- 24h Like tail (SWIPE_IMPLEMENTATION_SPEC L353): a Like
                        -- sent in travel mode only counts for matching while the
                        -- session is active OR within 24h after it ended. Likes
                        -- without a travel_location_id are always valid.
                        SELECT EXISTS (
                            SELECT 1 FROM swipe_actions sa
                            LEFT JOIN travel_locations tl
                              ON tl.id = sa.travel_location_id
                            WHERE sa.actor_user_id = ?
                              AND sa.target_user_id = ?
                              AND sa.action IN ('like', 'pineapple_like')
                              AND sa.rewound_at IS NULL
                              AND (
                                  sa.travel_location_id IS NULL
                                  OR tl.active_until IS NULL
                                  OR tl.active_until > now() - INTERVAL '24 hours'
                              )
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

    override suspend fun rewindSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        session: SwipeDeckSessionRecord,
        card: SwipeDeckSessionCardRecord,
        rewoundAt: Instant
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                // RETURNING consumed_inventory_id so we can refund the exact Pineapple
                // Like premium_inventory row in the same transaction. Likes/Passes have
                // a NULL inventory id and the refund step is a no-op for them.
                val refundedInventoryId: UUID? = connection.prepareStatement(
                    """
                    UPDATE swipe_actions sa
                    SET rewound_at = ?
                    WHERE sa.id = ?
                      AND sa.actor_user_id = ?
                      AND sa.target_user_id = ?
                      AND sa.rewound_at IS NULL
                      AND sa.action IN ('like', 'pass', 'pineapple_like')
                      AND NOT EXISTS (
                          SELECT 1
                          FROM matches m
                          WHERE (m.user_a = sa.actor_user_id AND m.user_b = sa.target_user_id)
                             OR (m.user_a = sa.target_user_id AND m.user_b = sa.actor_user_id)
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM safety_reports sr
                          WHERE sr.reporter_user_id = sa.actor_user_id
                            AND sr.reported_user_id = sa.target_user_id
                      )
                    RETURNING sa.consumed_inventory_id
                    """.trimIndent()
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(rewoundAt))
                    statement.setObject(2, actionId)
                    statement.setObject(3, actorUserId)
                    statement.setObject(4, targetUserId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            connection.rollback()
                            return@withContext false
                        }
                        rs.getObject("consumed_inventory_id", UUID::class.java)
                    }
                }

                if (refundedInventoryId != null) {
                    // Atomic refund in the same TX as the rewound_at update. If the
                    // referenced row was deleted (ON DELETE SET NULL nulled out the
                    // pointer by then), the update touches 0 rows and we proceed —
                    // the credit is effectively forfeited rather than rolled back.
                    connection.prepareStatement(
                        """
                        UPDATE premium_inventory
                        SET quantity = quantity + 1,
                            updated_at = ?
                        WHERE id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(rewoundAt))
                        statement.setObject(2, refundedInventoryId)
                        statement.executeUpdate()
                    }
                }

                connection.insertDeckSession(session)
                connection.insertDeckCards(listOf(card))
                connection.commit()
                true
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

    private fun Connection.insertDeckSession(session: SwipeDeckSessionRecord) {
        prepareStatement(
            """
            INSERT INTO swipe_deck_sessions (
                id,
                deck_session_token,
                deck_session_token_hash,
                viewer_user_id,
                scope_hash,
                radius_meters,
                page_limit,
                filter_payload,
                generated_at,
                expires_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (id) DO UPDATE
            SET updated_at = now()
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, session.sessionId)
            statement.setString(2, session.deckSessionToken)
            statement.setString(3, session.deckSessionTokenHash)
            statement.setObject(4, session.viewerUserId)
            statement.setString(5, session.scopeHash)
            statement.setInt(6, session.radiusMeters)
            statement.setInt(7, session.pageLimit)
            statement.setString(8, session.filterPayload)
            statement.setTimestamp(9, Timestamp.from(session.generatedAt))
            statement.setTimestamp(10, Timestamp.from(session.expiresAt))
            statement.executeUpdate()
        }
    }

    private fun Connection.insertDeckCards(cards: List<SwipeDeckSessionCardRecord>) {
        if (cards.isEmpty()) return
        prepareStatement(
            """
            INSERT INTO swipe_deck_cards (
                id,
                deck_session_id,
                viewer_user_id,
                target_user_id,
                card_token_hash,
                position,
                dealt_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent()
        ).use { statement ->
            for (card in cards) {
                statement.setObject(1, card.cardId)
                statement.setObject(2, card.sessionId)
                statement.setObject(3, card.viewerUserId)
                statement.setObject(4, card.targetUserId)
                statement.setString(5, card.cardTokenHash)
                statement.setInt(6, card.position)
                statement.setTimestamp(7, Timestamp.from(card.dealtAt))
                statement.addBatch()
            }
            statement.executeBatch()
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

    private fun ResultSet.toSwipeDeckSessionRecord(): SwipeDeckSessionRecord =
        SwipeDeckSessionRecord(
            sessionId = getObject("id", UUID::class.java),
            deckSessionToken = getString("deck_session_token"),
            deckSessionTokenHash = getString("deck_session_token_hash"),
            viewerUserId = getObject("viewer_user_id", UUID::class.java),
            scopeHash = getString("scope_hash"),
            radiusMeters = getInt("radius_meters"),
            pageLimit = getInt("page_limit"),
            filterPayload = getString("filter_payload"),
            generatedAt = getTimestamp("generated_at").toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant()
        )

    private fun Connection.findSwipeActionById(actionId: UUID, actorUserId: UUID): SwipeActionRecord? =
        prepareStatement(
            """
            SELECT sa.id AS action_id,
                   sa.actor_user_id,
                   sa.target_user_id,
                   sa.action,
                   sa.note_text,
                   sa.created_at AS action_created_at,
                   sa.rewound_at,
                   m.id AS match_id,
                   m.user_a,
                   m.user_b,
                   m.created_at AS match_created_at
            FROM swipe_actions sa
            LEFT JOIN matches m
              ON (
                  (m.user_a = sa.actor_user_id AND m.user_b = sa.target_user_id)
                  OR (m.user_a = sa.target_user_id AND m.user_b = sa.actor_user_id)
              )
            WHERE sa.id = ?
              AND sa.actor_user_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, actionId)
            statement.setObject(2, actorUserId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val matchId = rs.getObject("match_id") as? UUID
                    SwipeActionRecord(
                        actionId = rs.getObject("action_id", UUID::class.java),
                        actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                        targetUserId = rs.getObject("target_user_id", UUID::class.java),
                        action = rs.getString("action"),
                        noteText = rs.getString("note_text"),
                        createdAt = rs.getTimestamp("action_created_at").toInstant(),
                        rewoundAt = rs.getTimestamp("rewound_at")?.toInstant(),
                        match = matchId?.let {
                            MatchRecord(
                                matchId = it,
                                userA = rs.getObject("user_a", UUID::class.java),
                                userB = rs.getObject("user_b", UUID::class.java),
                                createdAt = rs.getTimestamp("match_created_at").toInstant()
                            )
                        }
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

private fun ResultSet.readStringArray(column: String): List<String> {
    val sqlArray = getArray(column) ?: return emptyList()
    return try {
        @Suppress("UNCHECKED_CAST")
        (sqlArray.array as Array<String?>?)?.filterNotNull().orEmpty()
    } finally {
        sqlArray.free()
    }
}

private fun ResultSet.readUuidArray(column: String): List<UUID> {
    val sqlArray = getArray(column) ?: return emptyList()
    return try {
        @Suppress("UNCHECKED_CAST")
        (sqlArray.array as Array<UUID?>?)?.filterNotNull().orEmpty()
    } finally {
        sqlArray.free()
    }
}
