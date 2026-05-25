package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.profiling.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcSwipeRepository(
    private val dataSource: DataSource,
    rankCacheTtl: Duration = Duration.ofSeconds(5),
    rankCacheMaxEntries: Int = 128,
    private val rankCacheWindow: Int = 128
) : SwipeRepository {
    private val deckRankCache = JdbcSwipeDeckRankCache(
        ttl = rankCacheTtl,
        maxEntries = rankCacheMaxEntries
    )

    override suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>
    ): SwipeDeckResult = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val viewerLocationKey = profile("swipe.deck.repo.viewerLocation") {
                connection.findViewerLocationCacheKey(viewerUserId)
            }

            if (viewerLocationKey == null) {
                return@withContext SwipeDeckResult(viewerHadLocation = false, cards = emptyList())
            }

            // Two-phase deck query (mirrors the Grid pattern from IMPLEMENTATION_AUDIT L70):
            //   Phase 1 ranks candidates with the cheap row-level filters and base profile
            //   columns only. The photo carousel and linked-partner mini hydration LATERALs
            //   are deferred to Phase 2 so we evaluate them for the LIMIT N rows we will
            //   actually return, not every candidate inside ST_DWithin.
            val rankedRows = profile("swipe.deck.repo.rank") {
                connection.queryDeckRankCached(
                    viewerLocationKey = viewerLocationKey,
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

    private fun Connection.queryDeckRankCached(
        viewerLocationKey: DeckLocationCacheKey,
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>
    ): List<DeckRankRow> {
        val activeTravelLocationsPresent = hasActiveTravelLocations()
        if (filters.linkedOnly == true) {
            val linkedTargetIds = listLinkedCandidateUserIdsNear(viewerUserId, radiusMeters)
            if (linkedTargetIds.isEmpty()) return emptyList()
            return queryDeckRank(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit,
                filters = filters.copy(linkedOnly = null),
                excludedTargetUserIds = excludedTargetUserIds,
                userSpecificViewerId = viewerUserId,
                forcedTargetUserIds = linkedTargetIds,
                includeTravelLocations = activeTravelLocationsPresent
            )
        }

        if (
            !filters.isRankCacheEligible() ||
            !deckRankCache.isEnabled()
        ) {
            return queryDeckRank(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit,
                filters = filters,
                excludedTargetUserIds = excludedTargetUserIds,
                userSpecificViewerId = viewerUserId,
                useNearestCandidateWindow = !activeTravelLocationsPresent
            )
        }

        val activeTravelTargetIds = if (activeTravelLocationsPresent) {
            listActiveTravelCandidateUserIdsNear(viewerUserId, radiusMeters)
        } else {
            emptySet()
        }
        val cacheWindow = rankCacheWindow.coerceAtLeast(limit)
        val cacheKey = DeckRankCacheKey(
            location = viewerLocationKey,
            radiusMeters = radiusMeters,
            filterKey = filters.rankCacheKey(),
            window = cacheWindow
        )
        val cachedRows = deckRankCache.load(cacheKey) {
            queryDeckRank(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = cacheWindow,
                filters = filters,
                excludedTargetUserIds = emptySet(),
                userSpecificViewerId = null,
                useNearestCandidateWindow = true
            )
        }
        val cachedTargetIds = cachedRows.mapTo(mutableSetOf()) { it.userId }
        val targetIdsForSwipeExclusion = cachedTargetIds + activeTravelTargetIds
        val swipedTargetIds = listActiveSwipedTargetIds(viewerUserId, targetIdsForSwipeExclusion)
        val excluded = excludedTargetUserIds + swipedTargetIds + viewerUserId
        val localPage = cachedRows.asSequence()
            .filterNot { it.userId in excluded }
            .take(limit)
            .toList()
        val travelRows = if (activeTravelTargetIds.isNotEmpty()) {
            queryDeckRankForcedTargets(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit,
                filters = filters,
                excludedTargetUserIds = excluded + localPage.map { it.userId },
                userSpecificViewerId = viewerUserId,
                forcedTargetUserIds = activeTravelTargetIds - excluded,
                includeTravelLocations = true
            )
        } else {
            emptyList()
        }
        val page = if (travelRows.isEmpty()) {
            localPage
        } else {
            (localPage + travelRows)
                .distinctBy { it.userId }
                .sortedWith(
                    compareByDescending<DeckRankRow> { it.isBoosted }
                        .thenByDescending { it.softScore }
                        .thenBy { it.distanceMeters }
                )
                .take(limit)
        }

        if (page.size >= limit || cachedRows.size < cacheWindow) {
            return page
        }

        return queryDeckRank(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit,
            filters = filters,
            excludedTargetUserIds = excludedTargetUserIds,
            userSpecificViewerId = viewerUserId,
            useNearestCandidateWindow = false
        )
    }

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

    override suspend fun findDeckSessionState(sessionId: UUID, viewerUserId: UUID): SwipeDeckSessionState? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val session = connection.prepareStatement(
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
                } ?: return@withContext null

                val targetIds = connection.prepareStatement(
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

                SwipeDeckSessionState(session = session, targetUserIds = targetIds)
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
            connection.markDeckCardActionRecorded(sessionId, cardTokenHash, targetUserId, action, now)
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
                    -- Travel-mode override for viewer side; target-side travel
                    -- must mirror queryDeck so dealt visiting cards remain
                    -- actionable.
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
                    LEFT JOIN travel_locations target_travel
                      ON target_travel.user_id = target.id
                     AND target_travel.status = 'active'
                     AND target_travel.active_until IS NULL
                    LEFT JOIN account_risk_states target_risk
                      ON target_risk.user_id = target.id
                    WHERE target.id = ?
                      AND target.status = 'active'
                      AND target.deleted_at IS NULL
                      AND target_profile.visibility_status = 'visible'
                      AND target_settings.swipe_visible = true
                      AND target_settings.incognito = false
                      AND (target_travel.id IS NULL OR target_settings.visible_while_traveling = true)
                      AND (?::boolean = false OR target_settings.allow_intro_notes = true)
                      AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                      AND (
                          ST_DWithin(v.geom, target_location.geom, ?)
                          OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))
                      )
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
                statement.setDouble(6, radiusMeters.toDouble())
                statement.setObject(7, viewerUserId)
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
                    SELECT COALESCE(tl.geom, ul.geom) AS geom,
                           tl.id AS travel_location_id
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
                    (
                        SELECT travel_location_id
                        FROM viewer_location
                        LIMIT 1
                    ) AS viewer_travel_location_id,
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
                        LEFT JOIN travel_locations target_travel
                          ON target_travel.user_id = target.id
                         AND target_travel.status = 'active'
                         AND target_travel.active_until IS NULL
                        LEFT JOIN account_risk_states target_risk
                          ON target_risk.user_id = target.id
                        WHERE target.id = ?
                          AND target.status = 'active'
                          AND target.deleted_at IS NULL
                          AND target_profile.visibility_status = 'visible'
                          AND target_settings.swipe_visible = true
                          AND target_settings.incognito = false
                          AND (target_travel.id IS NULL OR target_settings.visible_while_traveling = true)
                          AND (?::boolean = false OR target_settings.allow_intro_notes = true)
                          AND COALESCE(target_risk.tier, 'clean') NOT IN ('quarantine', 'terminate')
                          AND (
                              ST_DWithin(v.geom, target_location.geom, ?)
                              OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM swipe_actions sa
                              WHERE sa.actor_user_id = ?
                                AND sa.target_user_id = target.id
                                AND sa.rewound_at IS NULL
                          )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM user_blocks ub
                              WHERE (ub.blocker_user_id = ? AND ub.blocked_user_id = target.id)
                                 OR (ub.blocker_user_id = target.id AND ub.blocked_user_id = ?)
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
                statement.setDouble(12, radiusMeters.toDouble())
                statement.setObject(13, viewerUserId)
                statement.setObject(14, viewerUserId)
                statement.setObject(15, viewerUserId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    SwipeActionPreconditionCheck(
                        deckCardActionable = rs.getBoolean("deck_card_actionable"),
                        targetEligible = rs.getBoolean("target_eligible"),
                        viewerTravelLocationId = rs.getObject("viewer_travel_location_id", UUID::class.java)
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
                        return@withContext SwipeActionResult(
                            recorded = existing != null,
                            match = existing?.match,
                            newlyRecorded = false
                        )
                    }
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

    override suspend fun recordSwipeActionAndMarkCard(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        sessionId: UUID,
        cardTokenHash: String,
        now: Instant,
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
                        return@withContext SwipeActionResult(
                            recorded = existing != null,
                            match = existing?.match,
                            newlyRecorded = false
                        )
                    }
                }

                val match: MatchRecord? = if (action in POSITIVE_ACTIONS) {
                    val targetLikedActor = connection.prepareStatement(
                        """
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

                connection.markDeckCardActionRecorded(
                    sessionId = sessionId,
                    cardTokenHash = cardTokenHash,
                    targetUserId = targetUserId,
                    action = action,
                    now = now
                )

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
