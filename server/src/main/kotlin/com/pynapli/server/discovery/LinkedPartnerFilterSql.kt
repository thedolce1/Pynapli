package com.pynapli.server.discovery

import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID

/**
 * The "Linked" filter (`linkedOnly`) shared by the Grid rank, the Swipe deck rank and the linked-only
 * Swipe candidate lookup. `true` keeps exactly the people whose card shows this viewer a partner mini,
 * `false` exactly the people whose card shows none, `null` everyone. So the filter can never reveal a
 * partner the card hides: the owner's partner_visibility, the viewer's hidden list, a contact block
 * ("Hide from people you know") and a block either way all hide the partner here exactly as they hide
 * the mini (the hydration LATERAL in JdbcGridHydrationSql / JdbcSwipeDeckHydrationSql, plus the
 * services stripping a partner the viewer hid or who hides from the viewer).
 *
 * The partner rule itself is [CoupleVerifiedFilterSql.partnerShownToViewer], the same one the
 * couple-verified badge filter uses. A user has at most one active link (V16/V17 unique indexes), so
 * EXISTS here and the mini's `LIMIT 1` LATERAL look at the same partner.
 *
 * A Psync deck is shown to BOTH partners of the session, so it has two viewers: the partner must be
 * shown to every one of them (the strictest of the views, PSYNC_SPEC), and the hidden-from set is the
 * union of both partners' contact blocks. [viewerCount] repeats the per-viewer rule; bind the same
 * number of viewers.
 *
 * Viewer-specific, so a filter carrying it is never served from a shared rank cache
 * (`isGridRankCacheEligible` / `isRankCacheEligible`).
 */
object LinkedPartnerFilterSql {
    /**
     * `EXISTS (...)`: [user]'s active link points at a partner shown to every viewer. [user] is the
     * candidate's `users` alias and [settings] their `discovery_settings` alias (the owner's
     * partner_visibility). Placeholders, in order: the hidden-from-viewer id array, then
     * [CoupleVerifiedFilterSql.partnerShownToViewer]'s six viewer ids once per viewer. Bind with
     * [bindVisiblePartner].
     */
    fun visiblePartnerExists(
        user: String,
        settings: String,
        viewerCount: Int = 1,
    ): String {
        require(viewerCount >= 1)
        val partnerShown =
            CoupleVerifiedFilterSql.partnerShownToViewer(
                partnerUser = "lp_user",
                partnerProfile = "lp_profile",
                partnerSettings = "lp_ds",
                partnerRisk = "lp_risk",
                ownerSettings = settings,
                owner = "$user.id",
            )
        val everyViewer = List(viewerCount) { "AND $partnerShown" }.joinToString("\n")
        return """
            EXISTS (
                SELECT 1
                FROM user_profile_links lp_upl
                JOIN users lp_user ON lp_user.id = lp_upl.linked_user_id
                JOIN profiles lp_profile ON lp_profile.user_id = lp_user.id
                JOIN discovery_settings lp_ds ON lp_ds.user_id = lp_user.id
                LEFT JOIN account_risk_states lp_risk ON lp_risk.user_id = lp_user.id
                WHERE lp_upl.user_id = $user.id
                  AND lp_upl.status = 'active'
                  AND lp_upl.unlinked_at IS NULL
                  -- Contact blocks: a partner hiding from a viewer is never shown to them.
                  AND lp_user.id <> ALL(?::uuid[])
                  $everyViewer
            )
            """.trimIndent()
    }

    /**
     * `AND (linkedOnly IS NULL OR linkedOnly = <visible partner exists>)`. Placeholders: linkedOnly
     * twice, then [visiblePartnerExists]'s. Bind with [bind].
     */
    fun predicate(
        user: String,
        settings: String,
        viewerCount: Int = 1,
    ): String =
        """
        AND (
            ?::boolean IS NULL
            OR ?::boolean = ${visiblePartnerExists(user = user, settings = settings, viewerCount = viewerCount)}
        )
        """.trimIndent()

    /** Binds [predicate] for one viewer starting at [startIndex]; returns the next free index. */
    fun bind(
        statement: PreparedStatement,
        connection: Connection,
        startIndex: Int,
        linkedOnly: Boolean?,
        viewerUserId: UUID,
        hiddenFromViewerIds: Set<UUID>,
    ): Int = bind(statement, connection, startIndex, linkedOnly, listOf(viewerUserId), hiddenFromViewerIds)

    /** Binds [predicate] built with `viewerCount = viewers.size`; returns the next free index. */
    fun bind(
        statement: PreparedStatement,
        connection: Connection,
        startIndex: Int,
        linkedOnly: Boolean?,
        viewers: List<UUID>,
        hiddenFromViewerIds: Set<UUID>,
    ): Int {
        var index = startIndex
        statement.setObject(index++, linkedOnly, java.sql.Types.BOOLEAN)
        statement.setObject(index++, linkedOnly, java.sql.Types.BOOLEAN)
        return bindVisiblePartner(statement, connection, index, viewers, hiddenFromViewerIds)
    }

    /** Binds [visiblePartnerExists] built with `viewerCount = viewers.size`; returns the next free index. */
    fun bindVisiblePartner(
        statement: PreparedStatement,
        connection: Connection,
        startIndex: Int,
        viewers: List<UUID>,
        hiddenFromViewerIds: Set<UUID>,
    ): Int {
        var index = startIndex
        statement.setArray(index++, connection.createArrayOf("uuid", hiddenFromViewerIds.toTypedArray()))
        index = bindPartnerShown(statement, index, viewers)
        return index
    }

    /**
     * Binds [CoupleVerifiedFilterSql.partnerShownToViewer] repeated once per viewer: block check (both
     * directions), hidden-by-viewer, verified_only gate, matches_only LEAST/GREATEST, for each viewer.
     */
    fun bindPartnerShown(
        statement: PreparedStatement,
        startIndex: Int,
        viewers: List<UUID>,
    ): Int {
        var index = startIndex
        viewers.forEach { viewer -> repeat(PARTNER_SHOWN_VIEWER_PLACEHOLDERS) { statement.setObject(index++, viewer) } }
        return index
    }

    private const val PARTNER_SHOWN_VIEWER_PLACEHOLDERS = 6
}
