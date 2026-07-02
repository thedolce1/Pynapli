package com.pynapli.server.discovery

import com.pynapli.shared.profile.DiscoveryTagTaxonomies
import com.pynapli.shared.profile.ProfileTaxonomies

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
    // Grid-only Presence filter (FILTERS_SPEC "Active today"): only surface profiles whose owner has
    // been active in the last 24h (a non-revoked session seen within the window). Swipe ignores it,
    // the way Grid ignores verifiedPreferred. A hard existence gate.
    val activeTodayOnly: Boolean = false,
    // Grid-only Presence filters (FILTERS_SPEC "Recently joined" / "Traveling here"). recentlyJoined =
    // the account was created within the last week; travelingHere = the candidate is near the viewer via
    // an active travel/passport location (the card's "visiting" state). Hard gates; Swipe ignores them.
    val recentlyJoinedOnly: Boolean = false,
    val travelingHereOnly: Boolean = false,
    // FILTERS_SPEC L377 "Has photos": only surface profiles that have at least one approved,
    // visible public (profile-album) photo. Opt-in quality filter — default shows everyone,
    // including photo-less profiles. A hard existence gate, like verifiedOnly.
    val withPhotosOnly: Boolean = false,
    // FILTERS_SPEC "Has private album" (Media): only surface profiles that OWN a visible private album
    // with at least one approved photo. A discovery signal only — it does NOT grant access (albums are
    // still request/granted separately). Grid + Swipe, like withPhotosOnly. A hard existence gate.
    val hasPrivateAlbumOnly: Boolean = false,
    val intents: Set<String> = emptySet(),
    // Structured identity filters (PROFILE_FILTER_SYNC_SPEC: "orientation/gender compatibility" is
    // bidirectional). Hard match against profiles.gender / profiles.orientation, like the other grid
    // filters; self-describe profiles store a free-text key and are excluded from structured matching.
    val genders: Set<String> = emptySet(),
    val orientations: Set<String> = emptySet(),
    // FILTERS_SPEC L344-352: Verified offers three modes — "Verified only" (hard, via
    // verifiedOnly), "Verified preferred" (soft ranking weight), and "No preference".
    // This field carries the soft-ranking signal; Swipe uses it to boost verified
    // targets in the deck ranking. Grid ignores it.
    val verifiedPreferred: Boolean = false,
) {
    // Broad non-default filter signal used by Swipe's stricter paid gate.
    val hasAdvancedFilters: Boolean
        get() =
            minimumAge != null ||
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
                activeTodayOnly ||
                recentlyJoinedOnly ||
                travelingHereOnly ||
                withPhotosOnly ||
                hasPrivateAlbumOnly ||
                intents.isNotEmpty() ||
                genders.isNotEmpty() ||
                orientations.isNotEmpty()

    // Basic intent, trust, who, distance, age, online, and tonight filters stay free.
    // Paid "advanced" status is reserved for high-specificity lifestyle/logistics controls.
    val requiresAdvancedEntitlement: Boolean
        get() =
            lifestyleTags.isNotEmpty() ||
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
    activeTodayOnly: Boolean = false,
    recentlyJoinedOnly: Boolean = false,
    travelingHereOnly: Boolean = false,
    withPhotosOnly: Boolean = false,
    hasPrivateAlbumOnly: Boolean = false,
    intents: List<String> = emptyList(),
    genders: List<String> = emptyList(),
    orientations: List<String> = emptyList(),
    verifiedPreferred: Boolean = false,
    errorFactory: (String) -> RuntimeException,
): DiscoveryFilters {
    val min =
        minimumAge?.also { age ->
            if (age !in MIN_FILTER_AGE..MAX_FILTER_AGE) throw errorFactory("INVALID_DISCOVERY_FILTER")
        }
    val max =
        maximumAge?.also { age ->
            if (age !in MIN_FILTER_AGE..MAX_FILTER_AGE) throw errorFactory("INVALID_DISCOVERY_FILTER")
        }
    if (min != null && max != null && min > max) throw errorFactory("INVALID_DISCOVERY_FILTER")

    if (relationshipContexts.size > MAX_RELATIONSHIP_CONTEXT_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val contexts =
        relationshipContexts
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { value ->
                if (value.length > MAX_RELATIONSHIP_CONTEXT_LENGTH) {
                    throw errorFactory("INVALID_DISCOVERY_FILTER")
                }
            }.toSet()

    val styles =
        discoveryStyles
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { value ->
                if (value !in ALLOWED_DISCOVERY_STYLES) throw errorFactory("INVALID_DISCOVERY_FILTER")
            }.toSet()

    if (lifestyleTags.size > MAX_LIFESTYLE_TAG_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val tags =
        lifestyleTags
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { value ->
                if (value !in ALLOWED_LIFESTYLE_TAGS) throw errorFactory("INVALID_DISCOVERY_FILTER")
            }.toSet()

    if (logisticsTags.size > MAX_LOGISTICS_TAG_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val logistics =
        logisticsTags
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { value ->
                if (value !in ALLOWED_LOGISTICS_TAGS) throw errorFactory("INVALID_DISCOVERY_FILTER")
            }.toSet()

    if (intents.size > MAX_INTENT_FILTERS) {
        throw errorFactory("INVALID_DISCOVERY_FILTER")
    }
    val intentValues =
        intents
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { value ->
                if (value !in ALLOWED_INTENTS) throw errorFactory("INVALID_DISCOVERY_FILTER")
            }.toSet()

    if (genders.size > MAX_IDENTITY_FILTERS) throw errorFactory("INVALID_DISCOVERY_FILTER")
    val genderValues =
        genders
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { value ->
                if (value !in FILTERABLE_GENDERS) throw errorFactory("INVALID_DISCOVERY_FILTER")
            }.toSet()

    if (orientations.size > MAX_IDENTITY_FILTERS) throw errorFactory("INVALID_DISCOVERY_FILTER")
    val orientationValues =
        orientations
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { value ->
                if (value !in FILTERABLE_ORIENTATIONS) throw errorFactory("INVALID_DISCOVERY_FILTER")
            }.toSet()

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
        activeTodayOnly = activeTodayOnly,
        recentlyJoinedOnly = recentlyJoinedOnly,
        travelingHereOnly = travelingHereOnly,
        withPhotosOnly = withPhotosOnly,
        hasPrivateAlbumOnly = hasPrivateAlbumOnly,
        intents = intentValues,
        genders = genderValues,
        orientations = orientationValues,
        verifiedPreferred = verifiedPreferred,
    )
}

private const val MIN_FILTER_AGE = 18
private const val MAX_FILTER_AGE = 99
private const val MAX_RELATIONSHIP_CONTEXT_FILTERS = 8
private const val MAX_RELATIONSHIP_CONTEXT_LENGTH = 40

private val ALLOWED_DISCOVERY_STYLES = DiscoveryTagTaxonomies.discoveryStyles

private const val MAX_LIFESTYLE_TAG_FILTERS = 12

private val ALLOWED_LIFESTYLE_TAGS = DiscoveryTagTaxonomies.lifestyleTags

private const val MAX_LOGISTICS_TAG_FILTERS = 8

private val ALLOWED_LOGISTICS_TAGS = DiscoveryTagTaxonomies.logisticsTags

private val ADVANCED_LOGISTICS_TAGS = ALLOWED_LOGISTICS_TAGS - "public_meet_first"

private const val MAX_INTENT_FILTERS = 8

private val ALLOWED_INTENTS = DiscoveryTagTaxonomies.intents

private const val MAX_IDENTITY_FILTERS = 8

// Self-describe is a free-text profile value, not a structured token, so it is not a filter
// option (PROFILE_FILTER_SYNC_SPEC: self-describe profiles are excluded from structured filters).
private val FILTERABLE_GENDERS = ProfileTaxonomies.genderKeys - ProfileTaxonomies.SELF_DESCRIBE

private val FILTERABLE_ORIENTATIONS = ProfileTaxonomies.orientationKeys - ProfileTaxonomies.SELF_DESCRIBE
