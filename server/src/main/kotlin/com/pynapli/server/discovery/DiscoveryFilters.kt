package com.pynapli.server.discovery

data class DiscoveryFilters(
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    val relationshipContexts: Set<String> = emptySet(),
    val linkedOnly: Boolean? = null,
    val verifiedOnly: Boolean = false,
    val facePhotoVerifiedOnly: Boolean = false,
    val ageVerifiedOnly: Boolean = false,
    val discoveryStyles: Set<String> = emptySet(),
    val lifestyleTags: Set<String> = emptySet(),
    val logisticsTags: Set<String> = emptySet(),
    val tonightAvailableOnly: Boolean = false,
    val intents: Set<String> = emptySet(),
    // FILTERS_SPEC L344-352: Verified offers three modes — "Verified only" (hard, via
    // verifiedOnly), "Verified preferred" (soft ranking weight), and "No preference".
    // This field carries the soft-ranking signal; Swipe uses it to boost verified
    // targets in the deck ranking. Grid ignores it.
    val verifiedPreferred: Boolean = false
) {
    // Broad non-default filter signal used by Swipe's stricter paid gate.
    val hasAdvancedFilters: Boolean
        get() = minimumAge != null ||
            maximumAge != null ||
            relationshipContexts.isNotEmpty() ||
            linkedOnly != null ||
            verifiedOnly ||
            facePhotoVerifiedOnly ||
            ageVerifiedOnly ||
            discoveryStyles.isNotEmpty() ||
            lifestyleTags.isNotEmpty() ||
            logisticsTags.isNotEmpty() ||
            tonightAvailableOnly ||
            intents.isNotEmpty()

    // Basic intent, trust, who, distance, age, online, and tonight filters stay free.
    // Paid "advanced" status is reserved for high-specificity lifestyle/logistics controls.
    val requiresAdvancedEntitlement: Boolean
        get() = lifestyleTags.isNotEmpty() ||
            logisticsTags.any { it in ADVANCED_LOGISTICS_TAGS }
}

fun discoveryFiltersFrom(
    minimumAge: Int?,
    maximumAge: Int?,
    relationshipContexts: List<String>,
    linkedOnly: Boolean?,
    verifiedOnly: Boolean = false,
    facePhotoVerifiedOnly: Boolean = false,
    ageVerifiedOnly: Boolean = false,
    discoveryStyles: List<String> = emptyList(),
    lifestyleTags: List<String> = emptyList(),
    logisticsTags: List<String> = emptyList(),
    tonightAvailableOnly: Boolean = false,
    intents: List<String> = emptyList(),
    verifiedPreferred: Boolean = false,
    errorFactory: (String) -> RuntimeException
): DiscoveryFilters {
    val min = minimumAge?.also { age ->
        if (age !in MIN_FILTER_AGE..MAX_FILTER_AGE) throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val max = maximumAge?.also { age ->
        if (age !in MIN_FILTER_AGE..MAX_FILTER_AGE) throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    if (min != null && max != null && min > max) throw errorFactory("INVALID_DISCOVERY_FILTER")

    if (relationshipContexts.size > MAX_RELATIONSHIP_CONTEXT_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val contexts = relationshipContexts
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .onEach { value ->
            if (value.length > MAX_RELATIONSHIP_CONTEXT_LENGTH) {
                throw errorFactory("INVALID_DISCOVERY_FILTER")
            }
        }
        .toSet()

    val styles = discoveryStyles
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .onEach { value ->
            if (value !in ALLOWED_DISCOVERY_STYLES) throw errorFactory("INVALID_DISCOVERY_FILTER")
        }
        .toSet()

    if (lifestyleTags.size > MAX_LIFESTYLE_TAG_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val tags = lifestyleTags
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .onEach { value ->
            if (value !in ALLOWED_LIFESTYLE_TAGS) throw errorFactory("INVALID_DISCOVERY_FILTER")
        }
        .toSet()

    if (logisticsTags.size > MAX_LOGISTICS_TAG_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val logistics = logisticsTags
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .onEach { value ->
            if (value !in ALLOWED_LOGISTICS_TAGS) throw errorFactory("INVALID_DISCOVERY_FILTER")
        }
        .toSet()

    if (intents.size > MAX_INTENT_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val intentValues = intents
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .onEach { value ->
            if (value !in ALLOWED_INTENTS) throw errorFactory("INVALID_DISCOVERY_FILTER")
        }
        .toSet()

    return DiscoveryFilters(
        minimumAge = min,
        maximumAge = max,
        relationshipContexts = contexts,
        linkedOnly = linkedOnly,
        verifiedOnly = verifiedOnly,
        facePhotoVerifiedOnly = facePhotoVerifiedOnly,
        ageVerifiedOnly = ageVerifiedOnly,
        discoveryStyles = styles,
        lifestyleTags = tags,
        logisticsTags = logistics,
        tonightAvailableOnly = tonightAvailableOnly,
        intents = intentValues,
        verifiedPreferred = verifiedPreferred
    )
}

private const val MIN_FILTER_AGE = 18
private const val MAX_FILTER_AGE = 99
private const val MAX_RELATIONSHIP_CONTEXT_FILTERS = 8
private const val MAX_RELATIONSHIP_CONTEXT_LENGTH = 40

// Mirrors the CHECK constraint on profiles.discovery_style (V3__create_profile_foundation.sql).
private val ALLOWED_DISCOVERY_STYLES = setOf("solo", "together", "both", "social", "ask_in_chat")

private const val MAX_LIFESTYLE_TAG_FILTERS = 12

// Curated subset of LANGUAGE_AND_TAXONOMY_SPEC lifestyle terms surfaced as filterable tags.
// Profiles store the canonical lower-cased token in profiles.lifestyle_tags.
private val ALLOWED_LIFESTYLE_TAGS = setOf(
    "soft_swap",
    "full_swap",
    "same_room",
    "separate_room",
    "new_to_lifestyle",
    "experienced",
    "kink_friendly",
    "vanilla_social",
    "enm",
    "swinging"
)

private const val MAX_LOGISTICS_TAG_FILTERS = 8

// FILTERS_SPEC L170-184. "Distance radius" / "City/neighborhood" / "Travel dates" are
// modeled separately and are intentionally not in this set.
private val ALLOWED_LOGISTICS_TAGS = setOf(
    "hosts",
    "travels",
    "hotel_friendly",
    "public_meet_first",
    "club_event_only"
)

private val ADVANCED_LOGISTICS_TAGS = ALLOWED_LOGISTICS_TAGS - "public_meet_first"

private const val MAX_INTENT_FILTERS = 8

// FILTERS_SPEC L114-138 Intent group, minus values already covered by `discovery_style`
// (solo/together/both/social/ask_in_chat).
private val ALLOWED_INTENTS = setOf(
    "dating",
    "chat_first",
    "events",
    "open_to_singles",
    "open_to_couples",
    "open_to_linked_partners",
    "open_to_groups"
)
