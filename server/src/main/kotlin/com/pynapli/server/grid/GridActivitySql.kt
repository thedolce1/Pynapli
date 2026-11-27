package com.pynapli.server.grid

/**
 * SQL fragments for the Grid's activity signals (plan G3, G4, G11), shared by the rank query and the
 * page hydration so a card's flags and its tier/filter always agree. Every fragment depends only on
 * the candidate (never the viewer), so it is safe inside the shared, cross-viewer rank window.
 *
 * Fragments take the alias of a `users` row and an alias [tag] for their own subqueries, so two uses
 * in one statement never collide. None of them takes a bind parameter.
 */
internal object GridActivitySql {
    /**
     * Last activity: the later of the durable last-active row (written by every authenticated request,
     * at most once a minute; V106, backfilled from sessions in V127) and account creation, so a brand
     * new account counts as active. Only ever compared or bucketed server-side; never returned.
     */
    fun lastActiveAt(
        user: String,
        tag: String,
    ): String =
        """
        GREATEST(
            $user.created_at,
            (SELECT ${tag}_ula.last_active_at FROM user_last_active ${tag}_ula WHERE ${tag}_ula.user_id = $user.id)
        )
        """.trimIndent()

    /**
     * The owner has an approved, ready, visible public (profile-album) photo; with [addedWithin] (an
     * SQL interval literal such as `'24 hours'`), one added to the album within that window. Same
     * definition as the card thumbnail and the "Has photos" filter.
     */
    fun hasApprovedProfilePhoto(
        user: String,
        tag: String,
        addedWithin: String? = null,
    ): String =
        """
        EXISTS (
            SELECT 1
            FROM media_albums ${tag}_ma
            JOIN media_album_items ${tag}_mai
              ON ${tag}_mai.album_id = ${tag}_ma.id
             AND ${tag}_mai.removed_at IS NULL
            JOIN media_assets ${tag}_m
              ON ${tag}_m.id = ${tag}_mai.media_asset_id
             AND ${tag}_m.owner_user_id = ${tag}_ma.owner_user_id
             AND ${tag}_m.deleted_at IS NULL
            WHERE ${tag}_ma.owner_user_id = $user.id
              AND ${tag}_ma.kind = 'profile'
              AND ${tag}_ma.visibility_status = 'visible'
              AND ${tag}_ma.deleted_at IS NULL
              AND ${tag}_m.media_type = 'image'
              AND ${tag}_m.processing_status = 'ready'
              AND ${tag}_m.moderation_status = 'approved'
              AND ${tag}_m.intended_album_kind = 'profile'
              ${addedWithin?.let { "AND ${tag}_mai.added_at > now() - interval $it" }.orEmpty()}
        )
        """.trimIndent()

    /**
     * Demotion tier (plan G3): no activity for [DORMANT_AFTER] or no approved public photo. Such cards
     * rank after everyone else (boost bucket 2); see JdbcGridRankSql.
     */
    fun demoted(
        user: String,
        tag: String,
    ): String =
        "(${lastActiveAt(user, "${tag}_la")} < now() - interval $DORMANT_AFTER " +
            "OR NOT ${hasApprovedProfilePhoto(user, "${tag}_ph")})"

    /**
     * The same demotion rule as [demoted], over joined rows instead of correlated subqueries (G2-2
     * ticket 6): [lastActive] is a LEFT JOIN of `user_last_active` on the candidate, [photoOwner] a
     * LEFT JOIN of [approvedProfilePhotoOwners] (null = no approved public photo). GREATEST ignores the
     * NULL of a missing last-active row exactly as it ignored the subquery's NULL, so the tier and the
     * order are unchanged.
     */
    fun demotedJoined(
        user: String,
        lastActive: String,
        photoOwner: String,
    ): String =
        "(GREATEST($user.created_at, $lastActive.last_active_at) < now() - interval $DORMANT_AFTER " +
            "OR $photoOwner.user_id IS NULL)"

    /**
     * `SELECT DISTINCT user_id` of the owners, among the ids in the CTE/table [candidates] (a
     * `user_id` column), who have an approved, ready, visible public photo: [hasApprovedProfilePhoto]
     * as one set the planner builds once and hash-joins, instead of an EXISTS per candidate.
     */
    fun approvedProfilePhotoOwners(
        candidates: String,
        tag: String,
    ): String =
        """
        SELECT DISTINCT ${tag}_ma.owner_user_id AS user_id
        FROM $candidates ${tag}_c
        JOIN media_albums ${tag}_ma
          ON ${tag}_ma.owner_user_id = ${tag}_c.user_id
         AND ${tag}_ma.kind = 'profile'
         AND ${tag}_ma.visibility_status = 'visible'
         AND ${tag}_ma.deleted_at IS NULL
        JOIN media_album_items ${tag}_mai
          ON ${tag}_mai.album_id = ${tag}_ma.id
         AND ${tag}_mai.removed_at IS NULL
        JOIN media_assets ${tag}_m
          ON ${tag}_m.id = ${tag}_mai.media_asset_id
         AND ${tag}_m.owner_user_id = ${tag}_ma.owner_user_id
         AND ${tag}_m.deleted_at IS NULL
        WHERE ${tag}_m.media_type = 'image'
          AND ${tag}_m.processing_status = 'ready'
          AND ${tag}_m.moderation_status = 'approved'
          AND ${tag}_m.intended_album_kind = 'profile'
        """.trimIndent()

    /**
     * Fresh (plan G4, adapted from Grindr's rule, looser because we're sparser): the account is under
     * 72 hours old or added an approved public photo in the last 24 hours, AND was active in the last
     * 24 hours.
     */
    fun fresh(
        user: String,
        tag: String,
    ): String =
        "(($user.created_at > now() - interval $FRESH_ACCOUNT_AGE " +
            "OR ${hasApprovedProfilePhoto(user, "${tag}_ph", addedWithin = FRESH_PHOTO_AGE)}) " +
            "AND ${lastActiveAt(user, "${tag}_la")} > now() - interval $FRESH_ACTIVE_WITHIN)"

    /**
     * "Active today" (FILTERS_SPEC Presence): [lastActiveAt] within [ACTIVE_TODAY_WITHIN]. The card's
     * activeAgo caption buckets the same instant (GridActiveAgo: "today" or sooner means within 24
     * hours), so the filter keeps exactly the cards whose caption says active today or sooner.
     */
    fun activeToday(
        user: String,
        tag: String,
    ): String = "(${lastActiveAt(user, "${tag}_la")} >= now() - interval $ACTIVE_TODAY_WITHIN)"

    const val ACTIVE_TODAY_WITHIN = "'24 hours'"
    const val DORMANT_AFTER = "'30 days'"
    const val FRESH_ACCOUNT_AGE = "'72 hours'"
    const val FRESH_PHOTO_AGE = "'24 hours'"
    const val FRESH_ACTIVE_WITHIN = "'24 hours'"
}
