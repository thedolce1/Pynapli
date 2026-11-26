package com.pynapli.server.discovery

import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID

/**
 * The partner filters (FILTERS_SPEC "Partner", plan G2-3) and the "No single men" chip, shared by the
 * Grid (nearby and Visiting soon, through GridFilterSql), the Swipe deck rank and Psync (which runs
 * [matchingCandidateIds] a second time from the other session partner's view).
 *
 * Every predicate here tests the candidate's linked partner only through
 * [LinkedPartnerFilterSql.visiblePartnerExists], the exact gate behind the "Linked" filter and the
 * card's partner mini: the owner's partner_visibility, the viewer's hidden list, contact blocks, a
 * block either way, and the partner's status, visibility, incognito and moderation tier. A partner
 * this viewer may not see contributes nothing, so a partner filter never matches through a hidden
 * partner (the candidate behaves as "no visible partner"), and the result always agrees with the
 * tile. The partner's attributes are the partner's OWN declared profile values (gender key, display
 * age, orientation key, photo verification), never inferred from the candidate or from genders.
 *
 * Cost: when a filter is off its leading `?::boolean = false` short-circuits the OR, so the default
 * path runs no subquery. When on, it is one EXISTS per candidate that survived the cheaper filters:
 * a probe of the partial unique index `idx_user_profile_links_active_user` (at most one active link
 * per user), then primary-key lookups on users / profiles / discovery_settings /
 * account_risk_states for the partner, and the gate's own index probes (user_blocks,
 * user_hidden_profiles, matches only for matches_only owners). Same shape as the "Linked" filter, so
 * no new index is needed.
 *
 * Viewer-specific: a filter carrying any of these is never served from a shared rank cache.
 */
object PartnerFilterSql {
    /**
     * `AND (...) AND (...)`: the partner filters, then "No single men". [user], [profile] and
     * [settings] are the candidate's `users`, `profiles` and `discovery_settings` aliases. Bind with
     * [bind].
     */
    fun predicates(
        user: String,
        profile: String,
        settings: String,
    ): String {
        val partnerMatches =
            LinkedPartnerFilterSql.visiblePartnerExists(
                user = user,
                settings = settings,
                partnerCondition = PARTNER_CONDITION,
            )
        val anyVisiblePartner = LinkedPartnerFilterSql.visiblePartnerExists(user = user, settings = settings)
        return """
            -- Partner filters: the visibly linked partner's own gender, age, orientation, verification.
            AND (
                ?::boolean = false
                OR $partnerMatches
            )
            -- "No single men": not (declared man AND no partner shown to this viewer). IS DISTINCT
            -- FROM keeps people with no declared gender.
            AND (
                ?::boolean = false
                OR $profile.gender IS DISTINCT FROM 'man'
                OR $anyVisiblePartner
            )
            """.trimIndent()
    }

    /** Binds [predicates] starting at [startIndex]; returns the next free index. */
    fun bind(
        statement: PreparedStatement,
        connection: Connection,
        startIndex: Int,
        filters: DiscoveryFilters,
        viewerUserId: UUID,
        hiddenFromViewerIds: Set<UUID>,
    ): Int {
        var index = startIndex
        statement.setBoolean(index++, filters.hasPartnerFilters)
        index = LinkedPartnerFilterSql.bindVisiblePartner(statement, connection, index, viewerUserId, hiddenFromViewerIds)
        val genders = connection.createArrayOf("text", filters.partnerGenders.toTypedArray())
        val orientations = connection.createArrayOf("text", filters.partnerOrientations.toTypedArray())
        statement.setArray(index++, genders)
        statement.setArray(index++, genders)
        statement.setArray(index++, orientations)
        statement.setArray(index++, orientations)
        statement.setObject(index++, filters.partnerMinimumAge, java.sql.Types.INTEGER)
        statement.setObject(index++, filters.partnerMinimumAge, java.sql.Types.INTEGER)
        statement.setObject(index++, filters.partnerMaximumAge, java.sql.Types.INTEGER)
        statement.setObject(index++, filters.partnerMaximumAge, java.sql.Types.INTEGER)
        statement.setBoolean(index++, filters.partnerVerifiedOnly)
        statement.setBoolean(index++, filters.excludeSingleMen)
        return LinkedPartnerFilterSql.bindVisiblePartner(statement, connection, index, viewerUserId, hiddenFromViewerIds)
    }

    /**
     * Of [candidateIds], the ones that pass every partner-derived predicate ("Linked" / Singles, the
     * partner filters and "No single men") from [viewerUserId]'s view, with [hiddenFromViewerIds] the
     * people hiding from that viewer. Psync runs it for the session partner who did not rank the deck,
     * so a card passes only when it passes for both partners (the strictest of both views).
     */
    fun matchingCandidateIds(
        connection: Connection,
        viewerUserId: UUID,
        candidateIds: Collection<UUID>,
        filters: DiscoveryFilters,
        hiddenFromViewerIds: Set<UUID>,
    ): Set<UUID> {
        if (candidateIds.isEmpty()) return emptySet()
        val sql =
            """
            SELECT u.id
            FROM unnest(?::uuid[]) AS c(user_id)
            JOIN users u ON u.id = c.user_id
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            WHERE true
            ${LinkedPartnerFilterSql.predicate(user = "u", settings = "ds")}
            ${predicates(user = "u", profile = "p", settings = "ds")}
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setArray(index++, connection.createArrayOf("uuid", candidateIds.toTypedArray()))
            index = LinkedPartnerFilterSql.bind(statement, connection, index, filters.linkedOnly, viewerUserId, hiddenFromViewerIds)
            bind(statement, connection, index, filters, viewerUserId, hiddenFromViewerIds)
            statement.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getObject(1, UUID::class.java))
                }
            }
        }
    }

    /** Whether [filters] carries any partner-derived predicate Psync must re-check for the partner. */
    fun appliesTo(filters: DiscoveryFilters): Boolean = filters.linkedOnly != null || filters.hasPartnerFilters || filters.excludeSingleMen

    // Against the gated partner's own profile row (`lp_profile`, LinkedPartnerFilterSql's alias).
    // Placeholders: genders twice, orientations twice, min age twice, max age twice, verified.
    // Self-describe keys never match a structured value, as for the owner's own gender/orientation.
    private val PARTNER_CONDITION =
        """
        AND (cardinality(?::text[]) = 0 OR lp_profile.gender = ANY(?::text[]))
        AND (cardinality(?::text[]) = 0 OR lp_profile.orientation = ANY(?::text[]))
        AND (?::integer IS NULL OR lp_profile.display_age >= ?::integer)
        AND (?::integer IS NULL OR lp_profile.display_age <= ?::integer)
        AND (?::boolean = false OR lp_profile.verified_at IS NOT NULL)
        """.trimIndent()
}
