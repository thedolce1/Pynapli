package com.pynapli.shared.profile

/**
 * Canonical discovery token vocabularies — the single source of truth for the
 * **profile ↔ filter loop**: a profile may only carry values a filter can match, and a
 * filter may only offer values a profile can store. Shared between the Ktor server
 * (profile write-path validation in `ProfileRequestParsers` + discovery filter parsing in
 * `DiscoveryFilters`) and the Compose clients (filter chips + profile-edit chips + peek
 * labels), so the two can never drift. Mirrors the role [ProfileTaxonomies] plays for
 * gender/orientation.
 *
 * Tokens are the lower-snake values stored in the `profiles` columns
 * (`discovery_style`, `lifestyle_tags[]`, `logistics_tags[]`, `intents[]`). Per-context
 * display labels live with each UI (filter sheet, profile editor, peek) since they can
 * legitimately differ; only the tokens are canonical here.
 *
 * Client enums (`com.pynapli.app.grid.LifestyleTag` / `LogisticsTag` / `Intent` /
 * `DiscoveryStyle`) keep their own labels but their `serverValue`s are bound to these
 * sets by `DiscoveryTaxonomiesAlignmentTest`, so a drift fails the build.
 */
object DiscoveryTagTaxonomies {
    // Mirrors the CHECK constraint on profiles.discovery_style (V3__create_profile_foundation.sql).
    // `ask_in_chat` is a valid profile value but is intentionally NOT a grid/swipe filter option
    // (it's a profile-level preference, not a viewer-chosen filter), so the filter enum is a subset.
    val discoveryStyles = setOf("solo", "together", "both", "social", "ask_in_chat")

    // Curated subset of LANGUAGE_AND_TAXONOMY_SPEC lifestyle terms, grouped so a filter can
    // match "any of" inside a group and "all of" across groups (Soft swap + Same-room means
    // both, New + Experienced means either). Profiles store these in profiles.lifestyle_tags.
    val swapPreferences = setOf("soft_swap", "full_swap")
    val roomPreferences = setOf("same_room", "separate_room")
    val otherLifestyleTags = setOf("kink_friendly", "vanilla_social", "enm", "swinging")

    /** Tokens a profile may store in `profiles.lifestyle_tags`. */
    val lifestyleTags: Set<String> = swapPreferences + roomPreferences + otherLifestyleTags

    // One lifestyle experience level per account, stored in profiles.experience_level (V104).
    // Before V104 "new_to_lifestyle" / "experienced" were two independent (contradictory) tags;
    // the tokens are kept so saved filters and older clients still read the same way.
    const val EXPERIENCE_NEW = "new_to_lifestyle"
    const val EXPERIENCE_SOME = "some_experience"
    const val EXPERIENCE_EXPERIENCED = "experienced"
    val experienceLevels: List<String> = listOf(EXPERIENCE_NEW, EXPERIENCE_SOME, EXPERIENCE_EXPERIENCED)

    /**
     * Tokens a viewer may send in a discovery `lifestyleTags` filter: the profile tags plus the
     * experience levels (matched against `profiles.experience_level`).
     */
    val lifestyleFilterTags: Set<String> = lifestyleTags + experienceLevels

    /** A lifestyle filter split into its groups; an empty group does not filter. */
    data class LifestyleFilterGroups(
        val swap: Set<String>,
        val room: Set<String>,
        val experience: Set<String>,
        val other: Set<String>,
    )

    fun lifestyleFilterGroups(selected: Set<String>): LifestyleFilterGroups =
        LifestyleFilterGroups(
            swap = selected intersect swapPreferences,
            room = selected intersect roomPreferences,
            experience = selected intersect experienceLevels.toSet(),
            other = selected intersect otherLifestyleTags,
        )

    // FILTERS_SPEC L170-184. "Distance radius" / "City/neighborhood" / "Travel dates" are
    // modeled separately and are intentionally not in this set.
    val logisticsTags =
        setOf(
            "hosts",
            "travels",
            "hotel_friendly",
            "public_meet_first",
            "club_event_only",
        )

    // FILTERS_SPEC L114-138 Intent group, minus values already covered by `discovery_style`
    // (solo/together/both/social/ask_in_chat).
    val intents =
        setOf(
            "dating",
            "chat_first",
            "events",
            "open_to_singles",
            "open_to_couples",
            "open_to_linked_partners",
            "open_to_groups",
        )

    // Boundaries (consent-oriented, curated, never free text). Each account may mark an item
    // "ask first" or "not for me" (at most one stance per item). Stored as "<item>:<stance>"
    // tokens in profiles.boundaries (V104). As a filter a viewer names items, and profiles that
    // marked any of them "not for me" are hidden; "ask first" and unset both pass.
    const val BOUNDARY_ASK_FIRST = "ask_first"
    const val BOUNDARY_NOT_FOR_ME = "not_for_me"
    val boundaryStances: Set<String> = setOf(BOUNDARY_ASK_FIRST, BOUNDARY_NOT_FOR_ME)
    val boundaryItems: List<String> =
        listOf(
            "photos",
            "same_day",
            "home_meets",
            "separate_dates",
            "groups",
        )

    fun boundaryToken(
        item: String,
        stance: String,
    ): String = "$item:$stance"

    val boundaryTokens: Set<String> =
        boundaryItems.flatMap { item -> boundaryStances.map { boundaryToken(item, it) } }.toSet()

    fun boundaryItemOf(token: String): String = token.substringBefore(':')

    fun boundaryStanceOf(token: String): String = token.substringAfter(':', "")
}
