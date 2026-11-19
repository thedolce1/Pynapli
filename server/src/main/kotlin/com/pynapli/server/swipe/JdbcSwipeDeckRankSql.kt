package com.pynapli.server.swipe

import com.pynapli.server.discovery.CoupleVerifiedFilterSql
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.LifestyleFilterSql
import com.pynapli.server.distance.CoarseLocation
import java.sql.Connection
import java.util.UUID

/**
 * RANK phase of the Swipe deck. Location privacy (L-08..L-14) follows the Grid exactly:
 *  - everything is measured from the CENTRE OF THE VIEWER'S CELL at their effective location
 *    (travel point when travelling, else home), never from raw GPS;
 *  - membership is the cell-to-cell distance of the candidate's EFFECTIVE location within the
 *    radius step, so a traveller is matched only where they travel, and two people in one cell are
 *    always in or out together;
 *  - order is soft score, then the coarse km bucket (distance-independent for people who hide it),
 *    then a stable pseudo-random tiebreak seeded by the viewer's cell, then the id.
 *
 * Candidates come from two GiST-backed ST_DWithin lookups (home GPS, active travel point) padded
 * by [CoarseLocation.CELL_PAD_METERS], so the exact cell-to-cell test never misses anyone; or, with
 * [forcedTargetUserIds], from that id list (linked-only decks).
 *
 * Returns the first [limit] candidates in natural order (see `natural_rank`) plus up to
 * [boostedWindow] boosted candidates from anywhere in the radius step, all in natural order;
 * [SwipeDeckBoostPlacement] then lays out the page.
 *
 * When [userSpecificViewerId] is null the query is exclusion-free (no self or already-swiped
 * filter), so the window can be cached and shared by every viewer in the same cell, radius step
 * and filter class.
 */
internal fun Connection.queryDeckRank(
    viewerUserId: UUID,
    radiusMeters: Int,
    limit: Int,
    filters: DiscoveryFilters,
    excludedTargetUserIds: Set<UUID>,
    userSpecificViewerId: UUID?,
    forcedTargetUserIds: Set<UUID>? = null,
    boostedWindow: Int = limit,
): List<DeckRankRow> {
    val paddedRadius = radiusMeters + CoarseLocation.CELL_PAD_METERS
    val candidateSource =
        if (forcedTargetUserIds == null) {
            """
            SELECT near_home.user_id
            FROM viewer_location v
            JOIN user_locations near_home ON ST_DWithin(v.geom, near_home.geom, ?)
            UNION
            SELECT near_travel.user_id
            FROM viewer_location v
            JOIN travel_locations near_travel
              ON near_travel.status = 'active'
             AND near_travel.active_until IS NULL
             AND ST_DWithin(v.geom, near_travel.geom, ?)
            """.trimIndent()
        } else {
            "SELECT unnest(?::uuid[]) AS user_id"
        }
    val sql =
        """
        WITH viewer_location AS (
            -- Passport/travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354): an active travel point
            -- overrides GPS. Snapped to the viewer's cell (L-10/L-14).
            -- The order seed is built here once, not per candidate.
            SELECT cell.geom,
                   'deck:' || ST_AsText(cell.geom::geometry) AS order_seed
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            CROSS JOIN LATERAL (SELECT pynapli_location_cell(COALESCE(tl.geom, ul.geom)) AS geom) cell
            WHERE ul.user_id = ?
        ),
        candidate_ids AS (
$candidateSource
        ),
        -- MATERIALIZED, and the distance behind an OFFSET 0 fence: the coarse distance is a spheroid
        -- ST_Distance and the costliest thing here. Left to the planner it is inlined into the
        -- radius test, the output column and the order key, and so computed three times a candidate.
        candidates AS MATERIALIZED (
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
                   p.display_age,
                   p.bio,
                   array_remove(ARRAY[p.experience_level], NULL) || COALESCE(p.lifestyle_tags, '{}'::text[]) || COALESCE(p.logistics_tags, '{}'::text[]) AS profile_tags,
                   p.verified_at IS NOT NULL AS verified,
                   ds.show_distance,
                   -- Cell-to-cell distance from the target's EFFECTIVE location: never a raw GPS
                   -- distance, never a traveller's home.
                   coarse.distance_meters,
                   EXISTS (
                       SELECT 1
                       FROM boost_activations ba
                       WHERE ba.user_id = u.id
                         AND ba.status = 'active'
                         AND ba.ends_at > now()
                   ) AS is_boosted,
                   -- SWIPE_IMPLEMENTATION_SPEC L348: "Visiting" pill for a target in travel mode.
                   target_travel.id IS NOT NULL AS visiting,
                   -- FILTERS_SPEC L406-417: +1 when verifiedPreferred is on and the target is verified.
                   (CASE WHEN ?::boolean AND p.verified_at IS NOT NULL THEN 1 ELSE 0 END) AS soft_score
            FROM viewer_location v
            JOIN candidate_ids c ON true
            JOIN user_locations ul ON ul.user_id = c.user_id
            JOIN users u ON u.id = ul.user_id
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            LEFT JOIN account_risk_states ars ON ars.user_id = u.id
            LEFT JOIN travel_locations target_travel
              ON target_travel.user_id = u.id
             AND target_travel.status = 'active'
             AND target_travel.active_until IS NULL
            CROSS JOIN LATERAL (
                SELECT pynapli_coarse_distance_m(v.geom, COALESCE(target_travel.geom, ul.geom)) AS distance_meters
                OFFSET 0
            ) coarse
            WHERE (?::uuid IS NULL OR u.id <> ?::uuid)
              AND u.status = 'active'
              AND u.deleted_at IS NULL
              AND p.visibility_status = 'visible'
              AND ds.swipe_visible = true
              AND ds.incognito = false
              -- PROFILE_SETTINGS_SPEC "Visible while traveling".
              AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)
              AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
              -- Radius membership (L-09): the effective location's cell within the radius step.
              AND coarse.distance_meters <= ?
              AND (?::integer IS NULL OR p.display_age >= ?::integer)
              AND (?::integer IS NULL OR p.display_age <= ?::integer)
              AND (cardinality(?::text[]) = 0 OR lower(p.relationship_context) = ANY(?::text[]))
              AND (?::boolean = false OR p.verified_at IS NOT NULL)
              AND (?::boolean = false OR p.face_photo_verified_at IS NOT NULL)
              AND (?::boolean = false OR p.age_verified_at IS NOT NULL)
              -- "Verified couples only": exactly the couple-verified badge for this viewer. Also runs
              -- on the linked-only forced-id path, which reaches this query with linkedOnly cleared.
              ${CoupleVerifiedFilterSql.predicate(user = "u", profile = "p", settings = "ds")}
              AND (cardinality(?::text[]) = 0 OR p.discovery_style = ANY(?::text[]))
              AND (cardinality(?::text[]) = 0 OR (p.intents IS NOT NULL AND p.intents && ?::text[]))
              -- Lifestyle groups + boundaries (LifestyleFilterSql): same predicate on every surface.
              ${LifestyleFilterSql.predicates("p")}
              AND (cardinality(?::text[]) = 0 OR (p.logistics_tags IS NOT NULL AND p.logistics_tags && ?::text[]))
              -- Structured identity filters: hard match against the stored gender / orientation key.
              AND (cardinality(?::text[]) = 0 OR p.gender = ANY(?::text[]))
              AND (cardinality(?::text[]) = 0 OR p.orientation = ANY(?::text[]))
              -- FILTERS_SPEC L377 "Has photos": at least one approved, visible public photo.
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
              -- FILTERS_SPEC "Has private album": a visible private album with an approved photo.
              -- Discovery signal only; it grants no album access.
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
              -- Already swiped (live decisions only); skipped on the shared cached window.
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
        ),
        ranked AS (
            SELECT c.*,
                   -- L-08 order key: whole-km coarse bucket (distance-independent when hidden) and a
                   -- stable pseudo-random tiebreak seeded by the viewer's cell.
                   pynapli_order_bucket_m(c.show_distance, c.distance_meters, ?, v.order_seed, c.user_id) AS order_bucket,
                   pynapli_order_tiebreak(v.order_seed, c.user_id) AS order_tiebreak
            FROM candidates c
            CROSS JOIN viewer_location v
        ),
        numbered AS (
            SELECT r.*,
                   ROW_NUMBER() OVER (
                       ORDER BY r.soft_score DESC, r.order_bucket ASC, r.order_tiebreak ASC, r.user_id ASC
                   ) AS natural_rank,
                   ROW_NUMBER() OVER (
                       PARTITION BY r.is_boosted
                       ORDER BY r.soft_score DESC, r.order_bucket ASC, r.order_tiebreak ASC, r.user_id ASC
                   ) AS group_rank
            FROM ranked r
        )
        -- The window in natural order, plus the best boosted candidates however far down they sit
        -- (MONETIZATION_SPEC "Boost": a Boost lifts someone onto the page, SwipeDeckBoostPlacement).
        SELECT n.*
        FROM numbered n
        WHERE n.natural_rank <= ?
           OR (n.is_boosted AND n.group_rank <= ?)
        ORDER BY n.natural_rank ASC
        """.trimIndent()

    return prepareStatement(sql).use { statement ->
        val contexts = createArrayOf("text", filters.relationshipContexts.toTypedArray())
        val styles = createArrayOf("text", filters.discoveryStyles.toTypedArray())
        val intents = createArrayOf("text", filters.intents.toTypedArray())
        val logisticsTags = createArrayOf("text", filters.logisticsTags.toTypedArray())
        val genders = createArrayOf("text", filters.genders.toTypedArray())
        val orientations = createArrayOf("text", filters.orientations.toTypedArray())
        val excludedIds = createArrayOf("uuid", excludedTargetUserIds.toTypedArray())
        // Bound in SQL order; the running index keeps added placeholders from shifting the rest.
        var index = 1
        statement.setObject(index++, viewerUserId)
        if (forcedTargetUserIds == null) {
            // Index pre-filter radius (home GPS + travel point), padded by the cell slack.
            statement.setDouble(index++, paddedRadius)
            statement.setDouble(index++, paddedRadius)
        } else {
            statement.setArray(index++, createArrayOf("uuid", forcedTargetUserIds.toTypedArray()))
        }
        statement.setBoolean(index++, filters.verifiedPreferred)
        statement.setNullableUuid(index++, userSpecificViewerId)
        statement.setNullableUuid(index++, userSpecificViewerId)
        // Exact cell-to-cell radius membership.
        statement.setDouble(index++, radiusMeters.toDouble())
        statement.setObject(index++, filters.minimumAge)
        statement.setObject(index++, filters.minimumAge)
        statement.setObject(index++, filters.maximumAge)
        statement.setObject(index++, filters.maximumAge)
        statement.setArray(index++, contexts)
        statement.setArray(index++, contexts)
        statement.setBoolean(index++, filters.verifiedOnly)
        statement.setBoolean(index++, filters.facePhotoVerifiedOnly)
        statement.setBoolean(index++, filters.ageVerifiedOnly)
        index = CoupleVerifiedFilterSql.bind(statement, index, filters.verifiedCouplesOnly, viewerUserId)
        statement.setArray(index++, styles)
        statement.setArray(index++, styles)
        statement.setArray(index++, intents)
        statement.setArray(index++, intents)
        index = LifestyleFilterSql.bind(statement, this, index, filters.lifestyleTags, filters.boundaryItems)
        statement.setArray(index++, logisticsTags)
        statement.setArray(index++, logisticsTags)
        statement.setArray(index++, genders)
        statement.setArray(index++, genders)
        statement.setArray(index++, orientations)
        statement.setArray(index++, orientations)
        statement.setBoolean(index++, filters.withPhotosOnly)
        statement.setBoolean(index++, filters.hasPrivateAlbumOnly)
        statement.setObject(index++, filters.linkedOnly)
        statement.setObject(index++, filters.linkedOnly)
        statement.setObject(index++, viewerUserId)
        statement.setObject(index++, viewerUserId)
        statement.setNullableUuid(index++, userSpecificViewerId)
        statement.setNullableUuid(index++, userSpecificViewerId)
        statement.setBoolean(index++, excludedTargetUserIds.isNotEmpty())
        statement.setArray(index++, excludedIds)
        // Order key: radius (hidden placement span).
        statement.setInt(index++, radiusMeters)
        // The window, and how many boosted candidates beyond it to bring along.
        statement.setInt(index++, limit)
        statement.setInt(index++, boostedWindow)
        statement.executeDeckRankRows()
    }
}
