package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import java.sql.Connection
import java.util.UUID

internal fun Connection.queryDeckRankForcedTargetsLocal(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int,
    filters: DiscoveryFilters,
    excludedTargetUserIds: Set<UUID>,
    userSpecificViewerId: UUID?,
    forcedTargetUserIds: Set<UUID>,
): List<DeckRankRow> {
    val sql =
        """
        WITH viewer_location AS (
            SELECT geom
            FROM user_locations
            WHERE user_id = ?
        ),
        forced_locations AS MATERIALIZED (
            SELECT ul.user_id,
                   ul.geom,
                   ST_Distance(v.geom, ul.geom) AS distance_meters,
                   v.geom <-> ul.geom AS distance_order
            FROM viewer_location v
            JOIN user_locations ul
              ON ul.user_id = ANY(?::uuid[])
             AND ST_DWithin(v.geom, ul.geom, ?)
        )
        SELECT u.id AS user_id,
               COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
               p.display_age,
               p.bio,
               COALESCE(p.lifestyle_tags, '{}'::text[]) || COALESCE(p.logistics_tags, '{}'::text[]) AS profile_tags,
               p.verified_at IS NOT NULL AS verified,
               ds.show_distance,
               fl.distance_meters,
               EXISTS (
                   SELECT 1
                   FROM boost_activations ba
                   WHERE ba.user_id = u.id
                     AND ba.status = 'active'
                     AND ba.ends_at > now()
               ) AS is_boosted,
               false AS visiting,
               (CASE WHEN ?::boolean AND p.verified_at IS NOT NULL THEN 1 ELSE 0 END) AS soft_score
        FROM forced_locations fl
        JOIN users u ON u.id = fl.user_id
        JOIN profiles p ON p.user_id = u.id
        JOIN discovery_settings ds ON ds.user_id = u.id
        LEFT JOIN account_risk_states ars ON ars.user_id = u.id
        WHERE (?::uuid IS NULL OR u.id <> ?::uuid)
          AND u.status = 'active'
          AND u.deleted_at IS NULL
          AND p.visibility_status = 'visible'
          AND ds.swipe_visible = true
          AND ds.incognito = false
          AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
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
          AND (cardinality(?::text[]) = 0 OR p.gender = ANY(?::text[]))
          AND (cardinality(?::text[]) = 0 OR p.orientation = ANY(?::text[]))
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
        ORDER BY soft_score DESC, fl.distance_order
        LIMIT ?
        """.trimIndent()

    return prepareStatement(sql)
        .use { statement ->
            val forcedIds = createArrayOf("uuid", forcedTargetUserIds.toTypedArray())
            val contexts = createArrayOf("text", filters.relationshipContexts.toTypedArray())
            val styles = createArrayOf("text", filters.discoveryStyles.toTypedArray())
            val intents = createArrayOf("text", filters.intents.toTypedArray())
            val lifestyleTags = createArrayOf("text", filters.lifestyleTags.toTypedArray())
            val logisticsTags = createArrayOf("text", filters.logisticsTags.toTypedArray())
            val genders = createArrayOf("text", filters.genders.toTypedArray())
            val orientations = createArrayOf("text", filters.orientations.toTypedArray())
            val excludedIds = createArrayOf("uuid", excludedTargetUserIds.toTypedArray())
            statement.setObject(1, viewerUserId)
            statement.setArray(2, forcedIds)
            statement.setDouble(3, radiusMeters.toDouble())
            statement.setBoolean(4, filters.verifiedPreferred)
            statement.setNullableUuid(5, userSpecificViewerId)
            statement.setNullableUuid(6, userSpecificViewerId)
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
            statement.setNullableUuid(28, userSpecificViewerId)
            statement.setNullableUuid(29, userSpecificViewerId)
            statement.setBoolean(30, excludedTargetUserIds.isNotEmpty())
            statement.setArray(31, excludedIds)
            statement.setInt(32, limit)
            statement.executeDeckRankRows()
        }.sortedByDescending { it.isBoosted }
}

internal fun Connection.queryDeckRankForcedTargets(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int,
    filters: DiscoveryFilters,
    excludedTargetUserIds: Set<UUID>,
    userSpecificViewerId: UUID?,
    forcedTargetUserIds: Set<UUID>,
    includeTravelLocations: Boolean,
): List<DeckRankRow> {
    if (!includeTravelLocations) {
        return queryDeckRankForcedTargetsLocal(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit,
            filters = filters,
            excludedTargetUserIds = excludedTargetUserIds,
            userSpecificViewerId = userSpecificViewerId,
            forcedTargetUserIds = forcedTargetUserIds,
        )
    }
    val sql =
        """
        WITH viewer_location AS (
            SELECT COALESCE(tl.geom, ul.geom) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        ),
        forced_targets AS (
            SELECT unnest(?::uuid[]) AS user_id
        )
        SELECT u.id AS user_id,
               COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
               p.display_age,
               p.bio,
               COALESCE(p.lifestyle_tags, '{}'::text[]) || COALESCE(p.logistics_tags, '{}'::text[]) AS profile_tags,
               p.verified_at IS NOT NULL AS verified,
               ds.show_distance,
               ST_Distance(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters,
               EXISTS (
                   SELECT 1
                   FROM boost_activations ba
                   WHERE ba.user_id = u.id
                     AND ba.status = 'active'
                     AND ba.ends_at > now()
               ) AS is_boosted,
               target_travel.id IS NOT NULL AS visiting,
               (CASE WHEN ?::boolean AND p.verified_at IS NOT NULL THEN 1 ELSE 0 END) AS soft_score
        FROM viewer_location v
        JOIN forced_targets ft ON true
        JOIN users u ON u.id = ft.user_id
        JOIN user_locations ul ON ul.user_id = u.id
        JOIN profiles p ON p.user_id = u.id
        JOIN discovery_settings ds ON ds.user_id = u.id
        LEFT JOIN account_risk_states ars ON ars.user_id = u.id
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
          AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)
          AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
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
          AND (cardinality(?::text[]) = 0 OR p.gender = ANY(?::text[]))
          AND (cardinality(?::text[]) = 0 OR p.orientation = ANY(?::text[]))
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
        ORDER BY soft_score DESC, v.geom <-> COALESCE(target_travel.geom, ul.geom)
        LIMIT ?
        """.trimIndent()

    return prepareStatement(sql)
        .use { statement ->
            val forcedIds = createArrayOf("uuid", forcedTargetUserIds.toTypedArray())
            val contexts = createArrayOf("text", filters.relationshipContexts.toTypedArray())
            val styles = createArrayOf("text", filters.discoveryStyles.toTypedArray())
            val intents = createArrayOf("text", filters.intents.toTypedArray())
            val lifestyleTags = createArrayOf("text", filters.lifestyleTags.toTypedArray())
            val logisticsTags = createArrayOf("text", filters.logisticsTags.toTypedArray())
            val genders = createArrayOf("text", filters.genders.toTypedArray())
            val orientations = createArrayOf("text", filters.orientations.toTypedArray())
            val excludedIds = createArrayOf("uuid", excludedTargetUserIds.toTypedArray())
            statement.setObject(1, viewerUserId)
            statement.setArray(2, forcedIds)
            statement.setBoolean(3, filters.verifiedPreferred)
            statement.setNullableUuid(4, userSpecificViewerId)
            statement.setNullableUuid(5, userSpecificViewerId)
            statement.setDouble(6, radiusMeters.toDouble())
            statement.setDouble(7, radiusMeters.toDouble())
            statement.setObject(8, filters.minimumAge)
            statement.setObject(9, filters.minimumAge)
            statement.setObject(10, filters.maximumAge)
            statement.setObject(11, filters.maximumAge)
            statement.setArray(12, contexts)
            statement.setArray(13, contexts)
            statement.setBoolean(14, filters.verifiedOnly)
            statement.setBoolean(15, filters.facePhotoVerifiedOnly)
            statement.setBoolean(16, filters.ageVerifiedOnly)
            statement.setArray(17, styles)
            statement.setArray(18, styles)
            statement.setArray(19, intents)
            statement.setArray(20, intents)
            statement.setArray(21, lifestyleTags)
            statement.setArray(22, lifestyleTags)
            statement.setArray(23, logisticsTags)
            statement.setArray(24, logisticsTags)
            statement.setArray(25, genders)
            statement.setArray(26, genders)
            statement.setArray(27, orientations)
            statement.setArray(28, orientations)
            statement.setNullableUuid(29, userSpecificViewerId)
            statement.setNullableUuid(30, userSpecificViewerId)
            statement.setBoolean(31, excludedTargetUserIds.isNotEmpty())
            statement.setArray(32, excludedIds)
            statement.setInt(33, limit)
            statement.executeDeckRankRows()
        }.sortedByDescending { it.isBoosted }
}
