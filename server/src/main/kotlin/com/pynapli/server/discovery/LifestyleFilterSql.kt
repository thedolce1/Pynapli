package com.pynapli.server.discovery

import com.pynapli.shared.profile.DiscoveryTagTaxonomies
import java.sql.Connection
import java.sql.PreparedStatement

/**
 * The lifestyle + boundary hard-filter predicates shared by every discovery surface (Grid rank,
 * Swipe deck rank, Playing Tonight feed), so a profile matches the same way wherever it is seen.
 *
 *  - Lifestyle: "any of" inside a group, "all of" across groups (swap, room, experience, other).
 *    Swap/room/other match `profiles.lifestyle_tags`; experience matches the single
 *    `profiles.experience_level` (V104). A profile with nothing in a filtered group is excluded
 *    by that group (hard "has X"), exactly as the single overlap predicate did before.
 *  - Boundaries: a profile that marked any selected item "not for me" is hidden; "ask first"
 *    and unset pass (blank optional fields soft-include, PROFILE_FILTER_SYNC_SPEC).
 *  - "All of" (Grid, plan G2-4, `lifestyleMatchAll`, at most 3 tags): every selected swap, room and
 *    other tag must be on the profile (`@>`), and the experience level must equal the one selected.
 *    The group predicates then get empty arrays, so they cost nothing.
 *
 * Every predicate leads with `cardinality(?) = 0` so an unused group costs nothing.
 */
object LifestyleFilterSql {
    fun predicates(alias: String): String =
        """
        AND (cardinality(?::text[]) = 0 OR ($alias.lifestyle_tags IS NOT NULL AND $alias.lifestyle_tags && ?::text[]))
        AND (cardinality(?::text[]) = 0 OR ($alias.lifestyle_tags IS NOT NULL AND $alias.lifestyle_tags && ?::text[]))
        AND (cardinality(?::text[]) = 0 OR ($alias.lifestyle_tags IS NOT NULL AND $alias.lifestyle_tags && ?::text[]))
        AND (cardinality(?::text[]) = 0 OR $alias.experience_level = ANY(?::text[]))
        AND (cardinality(?::text[]) = 0 OR NOT (COALESCE($alias.boundaries, '{}'::text[]) && ?::text[]))
        AND (cardinality(?::text[]) = 0 OR ($alias.lifestyle_tags IS NOT NULL AND $alias.lifestyle_tags @> ?::text[]))
        """.trimIndent()

    /** Binds [predicates] starting at [startIndex]; returns the next free index. */
    fun bind(
        statement: PreparedStatement,
        connection: Connection,
        startIndex: Int,
        lifestyleTags: Set<String>,
        boundaryItems: Set<String>,
        // "All of" (Grid only): every selected tag must match, not any one per group.
        matchAll: Boolean = false,
    ): Int {
        val groups = DiscoveryTagTaxonomies.lifestyleFilterGroups(lifestyleTags)
        val allOf = if (matchAll) groups.swap + groups.room + groups.other else emptySet()
        val none = emptySet<String>()
        val notForMe =
            boundaryItems.map {
                DiscoveryTagTaxonomies.boundaryToken(it, DiscoveryTagTaxonomies.BOUNDARY_NOT_FOR_ME)
            }
        var index = startIndex
        listOf(
            if (matchAll) none else groups.swap,
            if (matchAll) none else groups.room,
            if (matchAll) none else groups.other,
            groups.experience,
            notForMe,
            allOf,
        ).forEach { values ->
            val array = connection.createArrayOf("text", values.toTypedArray())
            statement.setArray(index++, array)
            statement.setArray(index++, array)
        }
        return index
    }
}
