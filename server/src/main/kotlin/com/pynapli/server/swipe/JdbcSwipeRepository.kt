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
                connection.findDeckSession(sessionId, viewerUserId)
            }
        }

    override suspend fun findDeckSessionState(sessionId: UUID, viewerUserId: UUID): SwipeDeckSessionState? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findDeckSessionState(sessionId, viewerUserId)
            }
        }

    override suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.listDeckSessionTargetIds(sessionId)
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
            connection.isDeckCardActionable(
                sessionId = sessionId,
                cardTokenHash = cardTokenHash,
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                now = now
            )
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
            connection.recordSwipeActionTransaction(
                actionId = actionId,
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                action = action,
                noteText = noteText,
                consumedInventoryId = consumedInventoryId,
                travelLocationId = travelLocationId,
                matchIdFactory = matchIdFactory
            )
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
            connection.recordSwipeActionTransaction(
                actionId = actionId,
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                action = action,
                noteText = noteText,
                consumedInventoryId = consumedInventoryId,
                travelLocationId = travelLocationId,
                matchIdFactory = matchIdFactory
            ) {
                markDeckCardActionRecorded(
                    sessionId = sessionId,
                    cardTokenHash = cardTokenHash,
                    targetUserId = targetUserId,
                    action = action,
                    now = now
                )
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
            connection.rewindSwipeActionTransaction(
                actionId = actionId,
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                session = session,
                card = card,
                rewoundAt = rewoundAt
            )
        }
    }

    override suspend fun listMatches(viewerUserId: UUID, limit: Int): List<MatchSummaryRecord> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.listMatchSummaries(viewerUserId, limit)
        }
    }

}
