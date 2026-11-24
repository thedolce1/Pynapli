package com.pynapli.server.discovery

import java.sql.PreparedStatement
import java.util.UUID

/**
 * The "Verified couples only" predicate shared by the Grid rank and Swipe deck rank queries. It means
 * exactly what the couple-verified badge means for this viewer, so a card that passes always shows
 * the badge and a card that shows the badge always passes:
 *
 *  - the candidate is photo-verified (`profiles.verified_at`);
 *  - they have an active partner link, and that partner is the one the card's partner mini would show
 *    this viewer: the hydration LATERAL in JdbcGridHydrationSql / JdbcSwipeDeckHydrationSql (active,
 *    not deleted, visible, not incognito, not quarantined/terminated, no block either way with the
 *    viewer, and the owner's partner_visibility lets this viewer see them), and not someone the
 *    viewer hid (GridService / JdbcSwipeDeckQueryActions strip a hidden partner from the card);
 *  - that partner is photo-verified too.
 *
 * A user has at most one active link (V16/V17 unique indexes), so EXISTS here and the badge's
 * `LIMIT 1` LATERAL pick the same partner. The predicate is viewer-specific, so a filter carrying it
 * is never served from the shared rank cache (`isGridRankCacheEligible` / `isRankCacheEligible`).
 *
 * [user], [profile] and [settings] are the candidate's `users`, `profiles` and `discovery_settings`
 * aliases in the calling query. Leads with `?::boolean = false` so the default path costs nothing.
 */
object CoupleVerifiedFilterSql {
    fun predicate(
        user: String,
        profile: String,
        settings: String,
    ): String {
        val partnerShown =
            partnerShownToViewer(
                partnerUser = "cv_user",
                partnerProfile = "cv_profile",
                partnerSettings = "cv_ds",
                partnerRisk = "cv_risk",
                ownerSettings = settings,
                owner = "$user.id",
            )
        return """
            AND (
                ?::boolean = false
                OR (
                    $profile.verified_at IS NOT NULL
                    AND EXISTS (
                        SELECT 1
                        FROM user_profile_links cv_upl
                        JOIN users cv_user ON cv_user.id = cv_upl.linked_user_id
                        JOIN profiles cv_profile ON cv_profile.user_id = cv_user.id
                        JOIN discovery_settings cv_ds ON cv_ds.user_id = cv_user.id
                        LEFT JOIN account_risk_states cv_risk ON cv_risk.user_id = cv_user.id
                        WHERE cv_upl.user_id = $user.id
                          AND cv_upl.status = 'active'
                          AND cv_upl.unlinked_at IS NULL
                          AND cv_profile.verified_at IS NOT NULL
                          AND $partnerShown
                    )
                )
            )
            """.trimIndent()
    }

    /**
     * The profile's linked-partner rule: the partner the owner's card or profile shows this viewer. The
     * partner is active, not deleted, visible, not incognito, not quarantined/terminated, not blocked
     * either way with the viewer, not hidden by the viewer, and the owner's partner_visibility lets the
     * viewer see them. Aliases are the calling query's; six viewer placeholders, in order: the block check
     * (two), hidden-by-viewer, then [ownerShowsPartnerToViewer]'s three. Shared by the couples filter and
     * the "Met in person" partner tick, so the tick never names a partner the profile would hide.
     */
    fun partnerShownToViewer(
        partnerUser: String,
        partnerProfile: String,
        partnerSettings: String,
        partnerRisk: String,
        ownerSettings: String,
        owner: String,
    ): String =
        """
        $partnerUser.status = 'active'
        AND $partnerUser.deleted_at IS NULL
        AND $partnerProfile.visibility_status = 'visible'
        AND $partnerSettings.incognito = false
        AND COALESCE($partnerRisk.tier, 'clean') NOT IN ('quarantine', 'terminate')
        AND NOT EXISTS (
            SELECT 1
            FROM user_blocks cv_ub
            WHERE (cv_ub.blocker_user_id = ?::uuid AND cv_ub.blocked_user_id = $partnerUser.id)
               OR (cv_ub.blocker_user_id = $partnerUser.id AND cv_ub.blocked_user_id = ?::uuid)
        )
        AND NOT EXISTS (
            SELECT 1
            FROM user_hidden_profiles cv_hidden
            WHERE cv_hidden.hider_user_id = ?::uuid
              AND cv_hidden.hidden_user_id = $partnerUser.id
        )
        AND ${ownerShowsPartnerToViewer(settings = ownerSettings, owner = owner)}
        """.trimIndent()

    /**
     * The owner's partner_visibility allows this viewer to see their linked partner: public; verified
     * only when the viewer is photo-verified; matches only when the viewer and the owner have matched;
     * hidden never. [settings] is the owner's `discovery_settings` alias and [owner] the owner's id
     * expression. Three viewer placeholders, in order: verified check, then the match LEAST/GREATEST.
     * Shared with the messaging "Bring your partner" offer (JdbcMessagingPartnerOfferSql).
     */
    fun ownerShowsPartnerToViewer(
        settings: String,
        owner: String,
    ): String =
        """
        (
            $settings.partner_visibility = 'public'
            OR (
                $settings.partner_visibility = 'verified_only'
                AND EXISTS (
                    SELECT 1 FROM profiles pv_viewer
                    WHERE pv_viewer.user_id = ?::uuid AND pv_viewer.verified_at IS NOT NULL
                )
            )
            OR (
                $settings.partner_visibility = 'matches_only'
                AND EXISTS (
                    SELECT 1 FROM matches pv_match
                    WHERE LEAST(pv_match.user_a, pv_match.user_b) = LEAST(?::uuid, $owner)
                      AND GREATEST(pv_match.user_a, pv_match.user_b) = GREATEST(?::uuid, $owner)
                )
            )
        )
        """.trimIndent()

    /** Binds [predicate] starting at [startIndex]; returns the next free index. */
    fun bind(
        statement: PreparedStatement,
        startIndex: Int,
        enabled: Boolean,
        viewerUserId: UUID,
    ): Int {
        var index = startIndex
        statement.setBoolean(index++, enabled)
        // Block check (both directions), hidden-by-viewer, verified_only gate, matches_only LEAST/GREATEST.
        repeat(VIEWER_PLACEHOLDERS) { statement.setObject(index++, viewerUserId) }
        return index
    }

    private const val VIEWER_PLACEHOLDERS = 6
}
