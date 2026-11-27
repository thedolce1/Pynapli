package com.pynapli.server.discovery

import com.pynapli.shared.profile.DiscoveryTagTaxonomies
import com.pynapli.shared.profile.ProfileTaxonomies

data class DiscoveryFilters(
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    // Legacy, always empty from discoveryFiltersFrom: the Who tokens fold onto [linkedOnly] there, and
    // no query matches the free-text relationship_context any more. Kept so cache keys, cursor scopes
    // and stored filter JSON keep their shape.
    val relationshipContexts: Set<String> = emptySet(),
    // Who (FILTERS_SPEC): true = a partner is shown to this viewer ("Linked" / "Partnered"), false =
    // none is ("Singles"), null = everyone. LinkedPartnerFilterSql.
    val linkedOnly: Boolean? = null,
    val verifiedOnly: Boolean = false,
    val facePhotoVerifiedOnly: Boolean = false,
    val ageVerifiedOnly: Boolean = false,
    val discoveryStyles: Set<String> = emptySet(),
    // Lifestyle filter tokens (DiscoveryTagTaxonomies.lifestyleFilterTags): swap, room, experience
    // level and other tags. "Any of" within a group, "all of" across groups (LifestyleFilterSql).
    val lifestyleTags: Set<String> = emptySet(),
    // Boundary items (DiscoveryTagTaxonomies.boundaryItems): hide profiles that marked any of them
    // "not for me". Free and not age-gated (consent wording, low sensitivity).
    val boundaryItems: Set<String> = emptySet(),
    val logisticsTags: Set<String> = emptySet(),
    val tonightAvailableOnly: Boolean = false,
    // Grid-only Presence filter (FILTERS_SPEC "Active today"): only surface profiles whose owner has
    // been active in the last 24h (GridActivitySql.activeToday: user_last_active, the same source as the
    // card's activeAgo caption and Fresh). Swipe ignores it,
    // the way Grid ignores verifiedPreferred. A hard existence gate.
    val activeTodayOnly: Boolean = false,
    // Grid-only Presence filters. freshOnly = the "Fresh" quick chip (plan G4, GridActivitySql.fresh:
    // account under 72 h or a new approved photo in 24 h, and active in 24 h); it replaced "Recently
    // joined", and the old request field maps onto it. travelingHere = the candidate is near the viewer
    // via an active travel/passport location (the card's "visiting" state). Hard gates; Swipe ignores them.
    val freshOnly: Boolean = false,
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
    // "Verified couples only": the candidate passes only when the couple-verified badge would show
    // for this viewer (CoupleVerifiedFilterSql: both linked partners photo-verified and the partner
    // visible to the viewer). Implies "linked" without changing linkedOnly. Viewer-specific, so it
    // keeps the request off the shared rank cache. Free, like verifiedOnly.
    val verifiedCouplesOnly: Boolean = false,
    // Partner filters (FILTERS_SPEC "Partner", plan G2-3): tested against the candidate's visibly
    // linked partner's OWN declared profile (gender, display age, orientation, photo verification),
    // never inferred from the candidate. Every one of them runs inside the same visibility gate as
    // [linkedOnly] (PartnerFilterSql over LinkedPartnerFilterSql.visiblePartnerExists): a partner this
    // viewer may not see contributes nothing, so a partner filter never matches through a hidden
    // partner. Any of them on implies "Linked". Viewer-specific, so never served from a shared rank
    // cache. Free (founder decision 2), and hard on Swipe (never relaxed, like verifiedCouplesOnly).
    val partnerGenders: Set<String> = emptySet(),
    val partnerMinimumAge: Int? = null,
    val partnerMaximumAge: Int? = null,
    val partnerOrientations: Set<String> = emptySet(),
    val partnerVerifiedOnly: Boolean = false,
    // "No single men" quick chip: drops people whose own declared gender is "man" or "trans_man" (gender
    // identity, founder decision 2026-11-27) and who have no linked partner shown to this viewer (the
    // same gate). Men visibly linked to a partner still show; non-binary and other keys are untouched.
    val excludeSingleMen: Boolean = false,
    // "Haven't chatted" (Grid only, plan G2-4): drop people this viewer exchanged a direct message with
    // in the last 24 hours (GridFilterSql). Viewer-specific, so never served from the shared rank cache.
    val haventChattedOnly: Boolean = false,
    // Lifestyle "All of" (Grid, plan G2-4): every selected lifestyle tag must be on the profile, not any
    // one per group (LifestyleFilterSql). At most [MAX_LIFESTYLE_ALL_OF_TAGS] tags and one experience
    // level (a profile has one). Off = the usual any-of within a group, all-of across groups.
    val lifestyleMatchAll: Boolean = false,
) {
    /** Any partner-derived predicate is on (PartnerFilterSql runs its EXISTS). */
    val hasPartnerFilters: Boolean
        get() =
            partnerGenders.isNotEmpty() ||
                partnerMinimumAge != null ||
                partnerMaximumAge != null ||
                partnerOrientations.isNotEmpty() ||
                partnerVerifiedOnly

    /**
     * The partner filters and "No single men" for cursor scopes, deck scopes and rank cache keys.
     * Empty when none is set, so scopes issued before G2-3 keep their hash.
     */
    val partnerScopeKey: String
        get() =
            buildString {
                if (hasPartnerFilters) {
                    append("|partnerGenders=").append(partnerGenders.sorted().joinToString(","))
                    append("|partnerMinAge=").append(partnerMinimumAge ?: "")
                    append("|partnerMaxAge=").append(partnerMaximumAge ?: "")
                    append("|partnerOrientations=").append(partnerOrientations.sorted().joinToString(","))
                    append("|partnerVerifiedOnly=").append(partnerVerifiedOnly)
                }
                if (excludeSingleMen) append("|excludeSingleMen=true")
            }
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
    freshOnly: Boolean = false,
    travelingHereOnly: Boolean = false,
    withPhotosOnly: Boolean = false,
    hasPrivateAlbumOnly: Boolean = false,
    intents: List<String> = emptyList(),
    genders: List<String> = emptyList(),
    orientations: List<String> = emptyList(),
    verifiedPreferred: Boolean = false,
    boundaryItems: List<String> = emptyList(),
    verifiedCouplesOnly: Boolean = false,
    partnerGenders: List<String> = emptyList(),
    partnerMinimumAge: Int? = null,
    partnerMaximumAge: Int? = null,
    partnerOrientations: List<String> = emptyList(),
    partnerVerifiedOnly: Boolean = false,
    excludeSingleMen: Boolean = false,
    haventChattedOnly: Boolean = false,
    lifestyleMatchAll: Boolean = false,
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
    // FILTERS_SPEC "Who": Singles and Partnered are derived from the visible partner link, never from
    // the free-text relationship_context (the profile field is open text and nothing writes a fixed
    // vocabulary into it, so matching it never worked). The legacy tokens fold onto linkedOnly:
    // "single" means no partner shown to this viewer, "partnered" / "linked" means one is shown. The
    // two states are exclusive, so asking for both is the union: everyone (linkedOnly = null). Any
    // other context value is ignored. relationshipContexts is therefore always empty from here on.
    val wantsUnlinked = linkedOnly == false || contexts.any { it in SINGLE_CONTEXTS }
    val wantsLinked = linkedOnly == true || contexts.any { it in LINKED_CONTEXTS }
    val who =
        when {
            wantsLinked && wantsUnlinked -> null
            wantsLinked -> true
            wantsUnlinked -> false
            else -> null
        }

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

    // "All of up to 3" (plan G2-4): more tags, or two experience levels (one per profile, so nobody
    // could match), is refused rather than silently answered with an empty Grid.
    if (lifestyleMatchAll) {
        if (tags.size > MAX_LIFESTYLE_ALL_OF_TAGS) throw errorFactory("INVALID_DISCOVERY_FILTER")
        if (DiscoveryTagTaxonomies.lifestyleFilterGroups(tags).experience.size > 1) throw errorFactory("INVALID_DISCOVERY_FILTER")
    }

    if (boundaryItems.size > ALLOWED_BOUNDARY_ITEMS.size) throw errorFactory("INVALID_DISCOVERY_FILTER")
    val boundaries =
        boundaryItems
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { value ->
                if (value !in ALLOWED_BOUNDARY_ITEMS) throw errorFactory("INVALID_DISCOVERY_FILTER")
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

    // Partner filters: the same age bounds and the same structured taxonomies as the owner's own.
    val partnerMin =
        partnerMinimumAge?.also { age ->
            if (age !in MIN_FILTER_AGE..MAX_FILTER_AGE) throw errorFactory("INVALID_DISCOVERY_FILTER")
        }
    val partnerMax =
        partnerMaximumAge?.also { age ->
            if (age !in MIN_FILTER_AGE..MAX_FILTER_AGE) throw errorFactory("INVALID_DISCOVERY_FILTER")
        }
    if (partnerMin != null && partnerMax != null && partnerMin > partnerMax) throw errorFactory("INVALID_DISCOVERY_FILTER")
    val partnerGenderValues = identityValues(partnerGenders, FILTERABLE_GENDERS, errorFactory)
    val partnerOrientationValues = identityValues(partnerOrientations, FILTERABLE_ORIENTATIONS, errorFactory)

    return DiscoveryFilters(
        minimumAge = min,
        maximumAge = max,
        relationshipContexts = emptySet(),
        linkedOnly = who,
        verifiedOnly = verifiedOnly,
        facePhotoVerifiedOnly = facePhotoVerifiedOnly,
        ageVerifiedOnly = ageVerifiedOnly,
        discoveryStyles = styles,
        lifestyleTags = tags,
        boundaryItems = boundaries,
        logisticsTags = logistics,
        tonightAvailableOnly = tonightAvailableOnly,
        activeTodayOnly = activeTodayOnly,
        freshOnly = freshOnly,
        travelingHereOnly = travelingHereOnly,
        withPhotosOnly = withPhotosOnly,
        hasPrivateAlbumOnly = hasPrivateAlbumOnly,
        intents = intentValues,
        genders = genderValues,
        orientations = orientationValues,
        verifiedPreferred = verifiedPreferred,
        verifiedCouplesOnly = verifiedCouplesOnly,
        partnerGenders = partnerGenderValues,
        partnerMinimumAge = partnerMin,
        partnerMaximumAge = partnerMax,
        partnerOrientations = partnerOrientationValues,
        partnerVerifiedOnly = partnerVerifiedOnly,
        excludeSingleMen = excludeSingleMen,
        haventChattedOnly = haventChattedOnly,
        // Only meaningful with tags; an empty set stays the default so scopes keep their hash.
        lifestyleMatchAll = lifestyleMatchAll && tags.isNotEmpty(),
    )
}

private fun identityValues(
    values: List<String>,
    allowed: Set<String>,
    errorFactory: (String) -> RuntimeException,
): Set<String> {
    if (values.size > MAX_IDENTITY_FILTERS) throw errorFactory("INVALID_DISCOVERY_FILTER")
    return values
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .onEach { value -> if (value !in allowed) throw errorFactory("INVALID_DISCOVERY_FILTER") }
        .toSet()
}

private const val MIN_FILTER_AGE = 18
private const val MAX_FILTER_AGE = 99
private const val MAX_RELATIONSHIP_CONTEXT_FILTERS = 8
private const val MAX_RELATIONSHIP_CONTEXT_LENGTH = 40

// The legacy Who tokens clients send in relationshipContexts (GridFilters / SwipeFilters).
private val SINGLE_CONTEXTS = setOf("single")
private val LINKED_CONTEXTS = setOf("partnered", "linked")

private val ALLOWED_DISCOVERY_STYLES = DiscoveryTagTaxonomies.discoveryStyles

private const val MAX_LIFESTYLE_TAG_FILTERS = 12

/** Lifestyle "All of" takes at most this many tags (Grindr's tags: up to 3, all must match). */
const val MAX_LIFESTYLE_ALL_OF_TAGS = 3

// Includes the experience levels, so saved filters holding new_to_lifestyle / experienced keep working.
private val ALLOWED_LIFESTYLE_TAGS = DiscoveryTagTaxonomies.lifestyleFilterTags

private val ALLOWED_BOUNDARY_ITEMS = DiscoveryTagTaxonomies.boundaryItems.toSet()

private const val MAX_LOGISTICS_TAG_FILTERS = 8

private val ALLOWED_LOGISTICS_TAGS = DiscoveryTagTaxonomies.logisticsTags

private const val MAX_INTENT_FILTERS = 8

private val ALLOWED_INTENTS = DiscoveryTagTaxonomies.intents

private const val MAX_IDENTITY_FILTERS = 8

// Self-describe is a free-text profile value, not a structured token, so it is not a filter
// option (PROFILE_FILTER_SYNC_SPEC: self-describe profiles are excluded from structured filters).
private val FILTERABLE_GENDERS = ProfileTaxonomies.genderKeys - ProfileTaxonomies.SELF_DESCRIBE

private val FILTERABLE_ORIENTATIONS = ProfileTaxonomies.orientationKeys - ProfileTaxonomies.SELF_DESCRIBE
