package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import java.sql.Connection
import java.util.UUID

internal fun Connection.queryDeckRankNearestWindow(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int,
    filters: DiscoveryFilters,
    excludedTargetUserIds: Set<UUID>,
    userSpecificViewerId: UUID?
): List<DeckRankRow> {
    val candidateLimit = nearestCandidateWindowLimit(limit, filters)
    val sql = """
        WITH viewer_location AS (
            SELECT ul.geom AS geom
            FROM user_locations ul
            WHERE ul.user_id = ?
        ),
        nearest_locations AS MATERIALIZED (
            SELECT near.user_id,
                   near.geom,
                   ST_Distance(v.geom, near.geom) AS distance_meters,
                   v.geom <-> near.geom AS distance_order
            FROM viewer_location v
            JOIN LATERAL (
                SELECT user_id, geom
                FROM user_locations
                WHERE ST_DWithin(v.geom, geom, ?)
                ORDER BY geom <-> v.geom
                LIMIT ?
            ) near ON true
        )
        SELECT u.id AS user_id,
               COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
               p.display_age,
               p.bio,
               COALESCE(p.lifestyle_tags, '{}'::text[]) || COALESCE(p.logistics_tags, '{}'::text[]) AS profile_tags,
               p.verified_at IS NOT NULL AS verified,
               ds.show_distance,
               nl.distance_meters,
               EXISTS (
                   SELECT 1
                   FROM boost_activations ba
                   WHERE ba.user_id = u.id
                     AND ba.status = 'active'
                     AND ba.ends_at > now()
               ) AS is_boosted,
               false AS visiting,
               (CASE WHEN ?::boolean AND p.verified_at IS NOT NULL THEN 1 ELSE 0 END) AS soft_score
        FROM nearest_locations nl
        JOIN users u ON u.id = nl.user_id
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
        ORDER BY soft_score DESC, nl.distance_order
        LIMIT ?
    """.trimIndent()

    return prepareStatement(sql).use { statement ->
        val contexts = createArrayOf("text", filters.relationshipContexts.toTypedArray())
        val styles = createArrayOf("text", filters.discoveryStyles.toTypedArray())
        val intents = createArrayOf("text", filters.intents.toTypedArray())
        val lifestyleTags = createArrayOf("text", filters.lifestyleTags.toTypedArray())
        val logisticsTags = createArrayOf("text", filters.logisticsTags.toTypedArray())
        val excludedIds = createArrayOf("uuid", excludedTargetUserIds.toTypedArray())
        statement.setObject(1, viewerUserId)
        statement.setDouble(2, radiusMeters.toDouble())
        statement.setInt(3, candidateLimit)
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
        statement.setObject(24, filters.linkedOnly)
        statement.setObject(25, filters.linkedOnly)
        statement.setObject(26, viewerUserId)
        statement.setObject(27, viewerUserId)
        statement.setNullableUuid(28, userSpecificViewerId)
        statement.setNullableUuid(29, userSpecificViewerId)
        statement.setBoolean(30, excludedTargetUserIds.isNotEmpty())
        statement.setArray(31, excludedIds)
        statement.setInt(32, limit)
        statement.executeDeckRankRows()
    }.sortedByDescending { it.isBoosted }
}
