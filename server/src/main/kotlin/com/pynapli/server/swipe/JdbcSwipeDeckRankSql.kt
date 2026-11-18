package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import java.sql.Connection
import java.util.UUID

internal fun Connection.queryDeckRank(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int,
    filters: DiscoveryFilters,
    excludedTargetUserIds: Set<UUID>,
    userSpecificViewerId: UUID?,
    forcedTargetUserIds: Set<UUID> = emptySet(),
    useNearestCandidateWindow: Boolean = false,
    includeTravelLocations: Boolean = true,
): List<DeckRankRow> {
    if (forcedTargetUserIds.isNotEmpty()) {
        return queryDeckRankForcedTargets(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit,
            filters = filters,
            excludedTargetUserIds = excludedTargetUserIds,
            userSpecificViewerId = userSpecificViewerId,
            forcedTargetUserIds = forcedTargetUserIds,
            includeTravelLocations = includeTravelLocations,
        )
    }
    if (useNearestCandidateWindow) {
        val nearestRows =
            queryDeckRankNearestWindow(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit,
                filters = filters,
                excludedTargetUserIds = excludedTargetUserIds,
                userSpecificViewerId = userSpecificViewerId,
            )
        if (nearestRows.size >= limit) {
            return nearestRows
        }
    }

    // Ordering is coarse (L-08): soft score, then a whole-km cell-to-cell bucket
    // (distance-independent for people who hide distance), then a stable
    // pseudo-random tiebreak seeded by the viewer's cell - never exact distance,
    // which let neighbours who show distance bracket someone who hides it. The
    // candidate set is bounded by the index-driven ST_DWithin filter, so the sort
    // stays small. `is_boosted` is returned as a SELECT column (cheap EXISTS) and
    // the Kotlin side re-shuffles boosted users to the head of the page (spec
    // L320: boost increases impressions, not matches).
    val sql =
        """
        WITH viewer_location AS (
            -- Passport/travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354): an
            -- active travel_locations row overrides GPS for deck ranking.
            SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom
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
               -- to be - their travel point if active, else their real GPS.
               -- Cell-to-cell distance (AUDIT-10): never the raw GPS distance.
               pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters,
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
               -- state - null means physically local.
               target_travel.id IS NOT NULL AS visiting,
               -- FILTERS_SPEC L406-417 ranking inputs (B4 v1):
               -- +1 when verifiedPreferred is on AND the target is verified.
               -- Intent/style scoring is deferred; they would lose signal on the
               -- B3 relaxed pass (the relaxed filter set drops intents/styles, so
               -- a CASE that piggybacks on the filter param would always score 0).
               -- A v2 would carry separate "ranking hints" alongside filters.
               (CASE WHEN ?::boolean AND p.verified_at IS NOT NULL THEN 1 ELSE 0 END) AS soft_score,
               -- L-08: coarse order key (km bucket, distance-independent for hidden users) + stable
               -- pseudo-random tiebreak seeded by the viewer's cell. Never exact distance.
               pynapli_order_bucket_m(ds.show_distance, pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)), $radiusMeters, 'deck:' || ST_AsText(v.geom::geometry), u.id) AS order_bucket,
               pynapli_order_tiebreak('deck:' || ST_AsText(v.geom::geometry), u.id) AS order_tiebreak
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
        WHERE (?::uuid IS NULL OR u.id <> ?::uuid)
          AND u.status = 'active'
          AND u.deleted_at IS NULL
          AND p.visibility_status = 'visible'
          AND ds.swipe_visible = true
          AND ds.incognito = false
          -- PROFILE_SETTINGS_SPEC L221-245 "Visible while traveling":
          -- when a target is in travel mode AND opted out, they're hidden
          -- from every discovery surface for the duration of the session.
          AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)
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
          -- Structured identity filters: hard match against the profile's stored gender /
          -- orientation key (self-describe keys never match a structured filter value).
          AND (cardinality(?::text[]) = 0 OR p.gender = ANY(?::text[]))
          AND (cardinality(?::text[]) = 0 OR p.orientation = ANY(?::text[]))
          -- FILTERS_SPEC L377 "Has photos": opt-in gate to at least one approved, visible
          -- public (profile-album) photo. Same definition as the profile photo projection.
          AND (
              ?::boolean = false
              OR EXISTS (
                  SELECT 1
                  FROM media_albums has_photo_ma
                  JOIN media_album_items has_photo_mai
                    ON has_photo_mai.album_id = has_photo_ma.id
                   AND has_photo_mai.removed_at IS NULL
                  JOIN media_assets has_photo_m
                    ON has_photo_m.id = has_photo_mai.media_asset_id
                   AND has_photo_m.owner_user_id = has_photo_ma.owner_user_id
                   AND has_photo_m.deleted_at IS NULL
                  WHERE has_photo_ma.owner_user_id = u.id
                    AND has_photo_ma.kind = 'profile'
                    AND has_photo_ma.visibility_status = 'visible'
                    AND has_photo_ma.deleted_at IS NULL
                    AND has_photo_m.media_type = 'image'
                    AND has_photo_m.processing_status = 'ready'
                    AND has_photo_m.moderation_status = 'approved'
              )
          )
          -- FILTERS_SPEC "Has private album" (Media): opt-in gate to owning a visible private album
          -- with at least one approved photo. Discovery signal only — does not grant album access.
          AND (
              ?::boolean = false
              OR EXISTS (
                  SELECT 1
                  FROM media_albums has_private_ma
                  JOIN media_album_items has_private_mai
                    ON has_private_mai.album_id = has_private_ma.id
                   AND has_private_mai.removed_at IS NULL
                  JOIN media_assets has_private_m
                    ON has_private_m.id = has_private_mai.media_asset_id
                   AND has_private_m.owner_user_id = has_private_ma.owner_user_id
                   AND has_private_m.deleted_at IS NULL
                  WHERE has_private_ma.owner_user_id = u.id
                    AND has_private_ma.kind = 'private'
                    AND has_private_ma.visibility_status = 'visible'
                    AND has_private_ma.deleted_at IS NULL
                    AND has_private_m.media_type = 'image'
                    AND has_private_m.processing_status = 'ready'
                    AND has_private_m.moderation_status = 'approved'
              )
          )
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
          AND (
              ?::uuid IS NULL
              OR NOT EXISTS (
                  SELECT 1 FROM swipe_actions sa
                  WHERE sa.actor_user_id = ?::uuid
                    AND sa.target_user_id = u.id
                    AND sa.rewound_at IS NULL
              )
          )
          AND (
              ?::boolean = false
              OR u.id <> ALL(?::uuid[])
          )
          AND (
              ?::boolean = false
              OR u.id = ANY(?::uuid[])
          )
        -- Small in-memory sort over the candidate set already bounded by the
        -- index-driven ST_DWithin filter above.
        ORDER BY soft_score DESC, order_bucket ASC, order_tiebreak ASC
        LIMIT ?
        """.trimIndent()

    val rankedByDistance =
        prepareStatement(sql).use { statement ->
            val contexts = createArrayOf("text", filters.relationshipContexts.toTypedArray())
            val styles = createArrayOf("text", filters.discoveryStyles.toTypedArray())
            val intents = createArrayOf("text", filters.intents.toTypedArray())
            val lifestyleTags = createArrayOf("text", filters.lifestyleTags.toTypedArray())
            val logisticsTags = createArrayOf("text", filters.logisticsTags.toTypedArray())
            val genders = createArrayOf("text", filters.genders.toTypedArray())
            val orientations = createArrayOf("text", filters.orientations.toTypedArray())
            val excludedIds = createArrayOf("uuid", excludedTargetUserIds.toTypedArray())
            val forcedIds = createArrayOf("uuid", forcedTargetUserIds.toTypedArray())
            statement.setObject(1, viewerUserId)
            // $2: verifiedPreferred soft_score signal (SELECT). Placed early in the SQL
            // text so the SELECT param appears before the WHERE/ORDER BY params.
            statement.setBoolean(2, filters.verifiedPreferred)
            statement.setNullableUuid(3, userSpecificViewerId)
            statement.setNullableUuid(4, userSpecificViewerId)
            // $5 + $6: ST_DWithin radius bound twice - once against target's
            // real GPS, once against target's travel point.
            statement.setDouble(5, radiusMeters.toDouble())
            statement.setDouble(6, radiusMeters.toDouble())
            statement.setObject(7, filters.minimumAge)
            statement.setObject(8, filters.minimumAge)
            statement.setObject(9, filters.maximumAge)
            statement.setObject(10, filters.maximumAge)
            statement.setArray(11, contexts)
            statement.setArray(12, contexts)
            statement.setBoolean(13, filters.verifiedOnly)
            statement.setBoolean(14, filters.facePhotoVerifiedOnly)
            statement.setBoolean(15, filters.ageVerifiedOnly)
            statement.setArray(16, styles)
            statement.setArray(17, styles)
            statement.setArray(18, intents)
            statement.setArray(19, intents)
            statement.setArray(20, lifestyleTags)
            statement.setArray(21, lifestyleTags)
            statement.setArray(22, logisticsTags)
            statement.setArray(23, logisticsTags)
            statement.setArray(24, genders)
            statement.setArray(25, genders)
            statement.setArray(26, orientations)
            statement.setArray(27, orientations)
            // $28: FILTERS_SPEC "Has photos" gate.
            statement.setBoolean(28, filters.withPhotosOnly)
            // $29: FILTERS_SPEC "Has private album" gate.
            statement.setBoolean(29, filters.hasPrivateAlbumOnly)
            statement.setObject(30, filters.linkedOnly)
            statement.setObject(31, filters.linkedOnly)
            statement.setObject(32, viewerUserId)
            statement.setObject(33, viewerUserId)
            statement.setNullableUuid(34, userSpecificViewerId)
            statement.setNullableUuid(35, userSpecificViewerId)
            statement.setBoolean(36, excludedTargetUserIds.isNotEmpty())
            statement.setArray(37, excludedIds)
            statement.setBoolean(38, forcedTargetUserIds.isNotEmpty())
            statement.setArray(39, forcedIds)
            statement.setInt(40, limit)
            statement.executeDeckRankRows()
        }

    // Boosted users float to the head of the page, preserving distance order
    // within each bucket. Stable sort: boosted=true first, then SQL order.
    return rankedByDistance.sortedByDescending { it.isBoosted }
}
