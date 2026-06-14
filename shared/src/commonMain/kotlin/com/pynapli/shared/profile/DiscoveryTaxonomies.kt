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

    // Curated subset of LANGUAGE_AND_TAXONOMY_SPEC lifestyle terms surfaced as filterable tags.
    // Profiles store the canonical lower-cased token in profiles.lifestyle_tags.
    val lifestyleTags =
        setOf(
            "soft_swap",
            "full_swap",
            "same_room",
            "separate_room",
            "new_to_lifestyle",
            "experienced",
            "kink_friendly",
            "vanilla_social",
            "enm",
            "swinging",
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
}
