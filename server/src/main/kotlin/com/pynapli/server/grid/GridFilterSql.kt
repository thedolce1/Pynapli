package com.pynapli.server.grid

import com.pynapli.server.discovery.CoupleVerifiedFilterSql
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.LifestyleFilterSql
import com.pynapli.server.discovery.LinkedPartnerFilterSql
import com.pynapli.server.discovery.PartnerFilterSql
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID

/**
 * Every Grid filter predicate in one place, so the nearby rank (JdbcGridRankSql) and "Visiting soon"
 * (JdbcGridVisitingSoonSql) apply exactly the same filters: age, trust, verified couples, Linked /
 * Singles, discovery style, lifestyle and boundaries, logistics, Tonight, intents, gender, orientation,
 * has photos, active today, Fresh, traveling here and has private album.
 *
 * [user], [profile], [settings] and [travel] are the candidate's `users`, `profiles`,
 * `discovery_settings` and active `travel_locations` (LEFT JOIN) aliases in the calling query. The
 * fragment starts with `AND`. Bind it with [bind] at the position where it sits in the statement.
 */
internal object GridFilterSql {
    fun predicates(
        user: String = "u",
        profile: String = "p",
        settings: String = "ds",
        travel: String = "target_travel",
    ): String =
        """
        AND (?::integer IS NULL OR $profile.display_age >= ?::integer)
        AND (?::integer IS NULL OR $profile.display_age <= ?::integer)
        AND (?::boolean = false OR $profile.verified_at IS NOT NULL)
        AND (?::boolean = false OR $profile.face_photo_verified_at IS NOT NULL)
        AND (?::boolean = false OR $profile.age_verified_at IS NOT NULL)
        -- "Verified couples only": exactly the couple-verified badge for this viewer.
        ${CoupleVerifiedFilterSql.predicate(user = user, profile = profile, settings = settings)}
        -- "Linked" (linkedOnly): exactly whether the card shows this viewer a partner mini
        -- (LinkedPartnerFilterSql). Viewer-specific, so it never runs on the shared cached path.
        ${LinkedPartnerFilterSql.predicate(user = user, settings = settings)}
        -- Partner filters + "No single men" (PartnerFilterSql): the same partner gate as Linked.
        ${PartnerFilterSql.predicates(user = user, profile = profile, settings = settings)}
        AND (cardinality(?::text[]) = 0 OR $profile.discovery_style = ANY(?::text[]))
        -- Lifestyle groups + boundaries (LifestyleFilterSql): same predicate on every surface.
        ${LifestyleFilterSql.predicates(profile)}
        AND (cardinality(?::text[]) = 0 OR ($profile.logistics_tags IS NOT NULL AND $profile.logistics_tags && ?::text[]))
        AND (
            -- PLAYING_TONIGHT_SPEC: "playing tonight" means a live session. Only evaluated
            -- when the filter is on; the leading boolean short-circuits the default path.
            ?::boolean = false
            OR EXISTS (
                SELECT 1
                FROM tonight_session_participants tsp
                JOIN tonight_sessions ts ON ts.id = tsp.session_id
                WHERE tsp.user_id = $user.id
                  AND ts.status = 'active'
                  AND ts.paused_at IS NULL
                  AND ts.expires_at > now()
            )
        )
        AND (cardinality(?::text[]) = 0 OR ($profile.intents IS NOT NULL AND $profile.intents && ?::text[]))
        -- Structured identity filters: hard match against the profile's stored gender /
        -- orientation key (self-describe keys never match a structured filter value).
        AND (cardinality(?::text[]) = 0 OR $profile.gender = ANY(?::text[]))
        AND (cardinality(?::text[]) = 0 OR $profile.orientation = ANY(?::text[]))
        -- FILTERS_SPEC L377 "Has photos": opt-in gate to at least one approved, visible
        -- public (profile-album) photo. Same definition as the profile_photo projection.
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
                WHERE has_photo_ma.owner_user_id = $user.id
                  AND has_photo_ma.kind = 'profile'
                  AND has_photo_ma.visibility_status = 'visible'
                  AND has_photo_ma.deleted_at IS NULL
                  AND has_photo_m.media_type = 'image'
                  AND has_photo_m.processing_status = 'ready'
                  AND has_photo_m.moderation_status = 'approved'
                  AND has_photo_m.intended_album_kind = 'profile'
            )
        )
        -- FILTERS_SPEC "Active today" (Grid Presence): GridActivitySql.activeToday, the same
        -- last-active source as the card's activeAgo caption and Fresh. Only evaluated when on.
        AND (?::boolean = false OR ${GridActivitySql.activeToday(user = user, tag = "active_today")})
        -- "Fresh" quick chip (plan G4; replaced "Recently joined"): GridActivitySql.fresh, the
        -- same rule as the card's fresh flag. Only evaluated when the filter is on.
        AND (?::boolean = false OR ${GridActivitySql.fresh(user = user, tag = "fresh")})
        -- FILTERS_SPEC "Traveling here" (Grid Presence): the candidate is near the viewer via an
        -- active travel/passport location (the card's "visiting" state), not their home GPS.
        AND (?::boolean = false OR $travel.id IS NOT NULL)
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
                WHERE has_private_ma.owner_user_id = $user.id
                  AND has_private_ma.kind = 'private'
                  AND has_private_ma.visibility_status = 'visible'
                  AND has_private_ma.deleted_at IS NULL
                  AND has_private_m.media_type = 'image'
                  AND has_private_m.processing_status = 'ready'
                  AND has_private_m.moderation_status = 'approved'
            )
        )
        """.trimIndent()

    /** Binds [predicates] starting at [startIndex], in SQL order; returns the next free index. */
    fun bind(
        statement: PreparedStatement,
        connection: Connection,
        startIndex: Int,
        filters: DiscoveryFilters,
        viewerUserId: UUID,
        hiddenFromViewerIds: Set<UUID>,
    ): Int {
        val styles = connection.createArrayOf("text", filters.discoveryStyles.toTypedArray())
        val logisticsTags = connection.createArrayOf("text", filters.logisticsTags.toTypedArray())
        val intents = connection.createArrayOf("text", filters.intents.toTypedArray())
        val genders = connection.createArrayOf("text", filters.genders.toTypedArray())
        val orientations = connection.createArrayOf("text", filters.orientations.toTypedArray())
        var index = startIndex
        statement.setObject(index++, filters.minimumAge)
        statement.setObject(index++, filters.minimumAge)
        statement.setObject(index++, filters.maximumAge)
        statement.setObject(index++, filters.maximumAge)
        statement.setBoolean(index++, filters.verifiedOnly)
        statement.setBoolean(index++, filters.facePhotoVerifiedOnly)
        statement.setBoolean(index++, filters.ageVerifiedOnly)
        index = CoupleVerifiedFilterSql.bind(statement, connection, index, filters.verifiedCouplesOnly, viewerUserId, hiddenFromViewerIds)
        index = LinkedPartnerFilterSql.bind(statement, connection, index, filters.linkedOnly, viewerUserId, hiddenFromViewerIds)
        index = PartnerFilterSql.bind(statement, connection, index, filters, viewerUserId, hiddenFromViewerIds)
        statement.setArray(index++, styles)
        statement.setArray(index++, styles)
        index = LifestyleFilterSql.bind(statement, connection, index, filters.lifestyleTags, filters.boundaryItems)
        statement.setArray(index++, logisticsTags)
        statement.setArray(index++, logisticsTags)
        statement.setBoolean(index++, filters.tonightAvailableOnly)
        statement.setArray(index++, intents)
        statement.setArray(index++, intents)
        statement.setArray(index++, genders)
        statement.setArray(index++, genders)
        statement.setArray(index++, orientations)
        statement.setArray(index++, orientations)
        // FILTERS_SPEC "Has photos" gate.
        statement.setBoolean(index++, filters.withPhotosOnly)
        // FILTERS_SPEC "Active today" gate.
        statement.setBoolean(index++, filters.activeTodayOnly)
        // "Fresh" / "Traveling here" gates.
        statement.setBoolean(index++, filters.freshOnly)
        statement.setBoolean(index++, filters.travelingHereOnly)
        // FILTERS_SPEC "Has private album" gate.
        statement.setBoolean(index++, filters.hasPrivateAlbumOnly)
        return index
    }
}
