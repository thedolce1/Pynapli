package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.distance.DiscoveryRadiusSteps
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import kotlinx.serialization.Serializable

@Immutable
data class GridUiState(
    // The header pill: "Nearby", or the Travel city when Travel mode is on (never a precise place).
    val locationLabel: String = "Nearby",
    val filters: GridFilters = GridFilters.defaults,
    val cards: List<GridProfileCardUi> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val nextCursor: String? = null,
    val paywallMessage: String? = null,
    // The plan the paywall row sells (SubscriptionTier: "social" or "select"); names its button.
    val paywallUpgradeTier: String? = null,
    // Set when the server rejects a gated action with AGE_VERIFICATION_REQUIRED. Drives the
    // "Verify your age" prompt row; its CTA routes into the Profile-Hub age-verification flow.
    val verificationMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    // False only from app start (or sign-out) until the cold-start cache has been read: the body stays
    // a plain background meanwhile, so a cached Grid paints without a skeleton flashing first.
    val cacheChecked: Boolean = true,
    // People the last quiet refresh found who are not on screen yet: the "N new nearby" pill. The
    // shown order never changes under the finger; tapping the pill (or refreshing) applies it.
    val newNearbyCount: Int = 0,
    // The server widened a sparse first page beyond the chosen distance: a quiet note says so.
    val widened: Boolean = false,
    // Section 3: the plan that unlocks the locked cards ("social" or "select", the server's
    // unlockTier); names the boundary banner and focuses the plans sheet. Null when nothing is locked.
    val unlockTier: String? = null,
) {
    val verificationRequired: Boolean get() = verificationMessage != null

    /** Where the boundary banner sits in [cards] (before the first locked card), or null. */
    val lockedBannerIndex: Int? get() = firstLockedCardIndex(cards)
}

@Immutable
data class GridFilters(
    val online: Boolean = false,
    // FILTERS_SPEC "Who", from the visible partner link (server-decided): Singles = no partner shown
    // to the viewer, Linked = a partner shown. Exclusive states, so both on is the union (everyone).
    val singles: Boolean = false,
    val linked: Boolean = false,
    val verified: Boolean = false,
    val facePhotoVerified: Boolean = false,
    val ageVerified: Boolean = false,
    val discoveryStyles: Set<DiscoveryStyle> = emptySet(),
    val lifestyleTags: Set<LifestyleTag> = emptySet(),
    // Hide people who marked any of these boundaries "not for me" (free, not age-gated).
    val boundaryItems: Set<Boundary> = emptySet(),
    val logisticsTags: Set<LogisticsTag> = emptySet(),
    val intents: Set<Intent> = emptySet(),
    // Structured gender/orientation keys from ProfileTaxonomies (self-describe excluded).
    val genders: Set<String> = emptySet(),
    val orientations: Set<String> = emptySet(),
    // FILTERS_SPEC "Has photos": only show profiles with at least one approved public photo.
    val withPhotos: Boolean = false,
    // FILTERS_SPEC "Has private album": only show profiles that own a visible private album.
    val hasPrivateAlbum: Boolean = false,
    val tonightAvailable: Boolean = false,
    // FILTERS_SPEC "Active today" (Presence, grid-only): owner active in the last 24h.
    val activeToday: Boolean = false,
    // Plan G4 "Fresh" (a quick chip; replaces "Recently joined"): the server's Fresh rule, new
    // account or new photo and active lately. FILTERS_SPEC "Traveling here" (Presence, grid-only).
    val fresh: Boolean = false,
    val travelingHere: Boolean = false,
    // People whose announced planned trip brings them here in the next two weeks.
    val visitingSoon: Boolean = false,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    // "Verified couples only": both linked partners selfie-verified (the couple-verified badge).
    // Independent of [linked]; the server applies it without touching linkedOnly.
    val verifiedCouples: Boolean = false,
    // FILTERS_SPEC "Partner" (plan G2-3): the visibly linked partner's OWN declared gender, age,
    // orientation and photo verification. The server tests them only through the partner gate behind
    // Linked, so a partner hidden from this viewer never matches. They imply Linked, so with Singles
    // alone they are off ([partnerFiltersApply]) and never sent.
    val partnerGenders: Set<String> = emptySet(),
    val partnerMinimumAge: Int? = null,
    val partnerMaximumAge: Int? = null,
    val partnerOrientations: Set<String> = emptySet(),
    val partnerVerified: Boolean = false,
    // "No single men" quick chip: hide men with no partner shown to this viewer (linked men still show).
    val noSingleMen: Boolean = false,
    // "Haven't chatted" (Presence, Grid only): hide people this viewer exchanged a message with in the
    // last 24 hours. The server decides who that is; nothing about the conversations is sent back.
    val haventChatted: Boolean = false,
) {
    /** Any partner filter is chosen (whether or not the Who choice lets it apply). */
    val hasPartnerFilters: Boolean
        get() =
            partnerGenders.isNotEmpty() ||
                partnerMinimumAge != null ||
                partnerMaximumAge != null ||
                partnerOrientations.isNotEmpty() ||
                partnerVerified

    /**
     * Partner filters mean "people with a visible linked partner who ...", so they only apply when Who
     * is Linked or unrestricted. Singles alone (no partner shown) and a partner filter can never both
     * hold, so the sheet disables the Partner section there and the request leaves them out.
     */
    val partnerFiltersApply: Boolean get() = !(singles && !linked)

    /** These filters with partner filters dropped when Who rules them out (what is sent and stored). */
    fun withPartnerFiltersReconciled(): GridFilters =
        if (partnerFiltersApply) {
            this
        } else {
            copy(
                partnerGenders = emptySet(),
                partnerMinimumAge = null,
                partnerMaximumAge = null,
                partnerOrientations = emptySet(),
                partnerVerified = false,
            )
        }

    val activeCount: Int
        get() {
            var count = 0
            if (online) count += 1
            if (verifiedCouples) count += 1
            if (singles) count += 1
            if (linked) count += 1
            if (verified) count += 1
            if (facePhotoVerified) count += 1
            if (ageVerified) count += 1
            if (discoveryStyles.isNotEmpty()) count += 1
            if (lifestyleTags.isNotEmpty()) count += 1
            if (boundaryItems.isNotEmpty()) count += 1
            if (logisticsTags.isNotEmpty()) count += 1
            if (intents.isNotEmpty()) count += 1
            if (genders.isNotEmpty()) count += 1
            if (orientations.isNotEmpty()) count += 1
            if (withPhotos) count += 1
            if (hasPrivateAlbum) count += 1
            if (tonightAvailable) count += 1
            if (activeToday) count += 1
            if (fresh) count += 1
            if (travelingHere) count += 1
            if (visitingSoon) count += 1
            if (radiusMeters != DEFAULT_RADIUS_METERS) count += 1
            if (minimumAge != null || maximumAge != null) count += 1
            if (noSingleMen) count += 1
            if (haventChatted) count += 1
            if (partnerFiltersApply) {
                if (partnerGenders.isNotEmpty()) count += 1
                if (partnerMinimumAge != null || partnerMaximumAge != null) count += 1
                if (partnerOrientations.isNotEmpty()) count += 1
                if (partnerVerified) count += 1
            }
            return count
        }

    val isAnyActive: Boolean get() = activeCount > 0

    /**
     * The live Grid query: [toGridQueryRequest] plus the Section 3 opt-in, so the server keeps paging
     * past the plan limit with locked cards instead of stopping. Kept apart from [toGridQueryRequest],
     * which also writes saved presets and the stored filters, where the opt-in has no meaning.
     * G2-2: asks for the largest page; the server clamps it to the viewer's plan (Free 60, Social 90,
     * Select 120) and seals that size into the cursor. Presets keep [PAGE_SIZE].
     */
    fun toGridFeedRequest(cursor: String?): GridQueryRequestDto = toGridQueryRequest(cursor).copy(acceptLockedCards = true, limit = MAX_FEED_PAGE_REQUEST)

    fun toGridQueryRequest(cursor: String?): GridQueryRequestDto {
        // Singles travels as the "single" Who token, Linked as linkedOnly; the server folds both onto
        // the visible link (both on = everyone), so a saved preset with both restores both.
        val contexts = if (singles) listOf(SINGLES_CONTEXT) else emptyList()
        val partner = withPartnerFiltersReconciled()
        return GridQueryRequestDto(
            radiusMeters = snapRadius(radiusMeters),
            limit = PAGE_SIZE,
            cursor = cursor,
            minimumAge = minimumAge,
            maximumAge = maximumAge,
            relationshipContexts = contexts,
            linkedOnly = if (linked) true else null,
            onlineOnly = online,
            verifiedOnly = verified,
            facePhotoVerifiedOnly = facePhotoVerified,
            ageVerifiedOnly = ageVerified,
            discoveryStyles = discoveryStyles.map { it.serverValue },
            lifestyleTags = lifestyleTags.map { it.serverValue },
            boundaryItems = boundaryItems.map { it.serverValue },
            logisticsTags = logisticsTags.map { it.serverValue },
            tonightAvailableOnly = tonightAvailable,
            activeTodayOnly = activeToday,
            freshOnly = fresh,
            travelingHereOnly = travelingHere,
            visitingSoonOnly = visitingSoon,
            withPhotosOnly = withPhotos,
            hasPrivateAlbumOnly = hasPrivateAlbum,
            intents = intents.map { it.serverValue },
            genders = genders.toList(),
            orientations = orientations.toList(),
            verifiedCouplesOnly = verifiedCouples,
            partnerGenders = partner.partnerGenders.toList(),
            partnerMinimumAge = partner.partnerMinimumAge,
            partnerMaximumAge = partner.partnerMaximumAge,
            partnerOrientations = partner.partnerOrientations.toList(),
            partnerVerifiedOnly = partner.partnerVerified,
            excludeSingleMen = noSingleMen,
            haventChattedOnly = haventChatted,
        )
    }

    companion object {
        const val DEFAULT_RADIUS_METERS: Int = 50_000

        // The server tests only DiscoveryRadiusSteps (2, 5, 10, 25, 50, 100 km); these are its ends.
        const val MIN_RADIUS_METERS: Int = 2_000
        const val MAX_RADIUS_METERS: Int = 100_000
        const val MIN_FILTER_AGE: Int = 18
        const val MAX_FILTER_AGE: Int = 99
        const val PAGE_SIZE: Int = 60

        /** The live Grid asks for this many; the server answers with the plan's page size (G2-2). */
        const val MAX_FEED_PAGE_REQUEST: Int = 120
        const val SINGLES_CONTEXT: String = "single"

        // The retired "Partnered" token: a preset that carries it restores as Linked.
        const val PARTNERED_CONTEXT: String = "partnered"
        val defaults: GridFilters = GridFilters()

        /**
         * The radius the server searches for [meters]: clamped to the slider ends, then snapped UP to
         * the next DiscoveryRadiusSteps step, exactly as the server snaps it (L-09).
         */
        fun snapRadius(meters: Int): Int = DiscoveryRadiusSteps.snapUp(meters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS))

        /**
         * Rehydrate a saved preset payload back into a `GridFilters` instance.
         * Unknown discovery_style / lifestyle / logistics tokens are dropped silently
         * so older presets remain partially applicable after a taxonomy change; the
         * server re-validates on the next `/v1/grid/query` regardless.
         */
        fun fromGridQueryRequest(request: GridQueryRequestDto): GridFilters {
            val contexts = request.relationshipContexts.toSet()
            val styleByValue = DiscoveryStyle.entries.associateBy { it.serverValue }
            val lifestyleByValue = LifestyleTag.entries.associateBy { it.serverValue }
            val logisticsByValue = LogisticsTag.entries.associateBy { it.serverValue }
            val intentByValue = Intent.entries.associateBy { it.serverValue }
            return GridFilters(
                online = request.onlineOnly,
                singles = SINGLES_CONTEXT in contexts || request.linkedOnly == false,
                linked = request.linkedOnly == true || PARTNERED_CONTEXT in contexts,
                verified = request.verifiedOnly,
                facePhotoVerified = request.facePhotoVerifiedOnly,
                ageVerified = request.ageVerifiedOnly,
                discoveryStyles = request.discoveryStyles.mapNotNull { styleByValue[it] }.toSet(),
                lifestyleTags = request.lifestyleTags.mapNotNull { lifestyleByValue[it] }.toSet(),
                boundaryItems = Boundary.fromServerValues(request.boundaryItems),
                logisticsTags = request.logisticsTags.mapNotNull { logisticsByValue[it] }.toSet(),
                intents = request.intents.mapNotNull { intentByValue[it] }.toSet(),
                genders = request.genders.toSet(),
                orientations = request.orientations.toSet(),
                withPhotos = request.withPhotosOnly,
                hasPrivateAlbum = request.hasPrivateAlbumOnly,
                tonightAvailable = request.tonightAvailableOnly,
                activeToday = request.activeTodayOnly,
                // A preset saved before Fresh replaced "Recently joined" keeps meaning "new people".
                fresh = request.freshOnly || request.recentlyJoinedOnly,
                travelingHere = request.travelingHereOnly,
                visitingSoon = request.visitingSoonOnly,
                // A stored or saved off-step radius (from the old continuous slider) maps up exactly as the
                // server maps it, so the sheet shows the radius actually searched.
                radiusMeters = snapRadius(request.radiusMeters),
                minimumAge = request.minimumAge?.coerceIn(MIN_FILTER_AGE, MAX_FILTER_AGE),
                maximumAge = request.maximumAge?.coerceIn(MIN_FILTER_AGE, MAX_FILTER_AGE),
                verifiedCouples = request.verifiedCouplesOnly,
                partnerGenders = request.partnerGenders.toSet(),
                partnerMinimumAge = request.partnerMinimumAge?.coerceIn(MIN_FILTER_AGE, MAX_FILTER_AGE),
                partnerMaximumAge = request.partnerMaximumAge?.coerceIn(MIN_FILTER_AGE, MAX_FILTER_AGE),
                partnerOrientations = request.partnerOrientations.toSet(),
                partnerVerified = request.partnerVerifiedOnly,
                noSingleMen = request.excludeSingleMen,
                haventChatted = request.haventChattedOnly,
            )
        }
    }
}

/**
 * A one-tap filter the Grid can toggle. The header row shows Online, Fresh, Tonight, Verified and
 * "No single men" (plus the Favorites view chip, which is not a filter); Singles and Linked are
 * toggles in the filter sheet's Who section, not quick filters.
 */
enum class QuickFilterId {
    Online,
    Verified,
    Tonight,
    Fresh,
    NoSingleMen,
}

/**
 * Mirrors the values allowed by the `profiles.discovery_style` CHECK constraint
 * (V3 migration). Ask-in-chat is intentionally omitted from the grid filter UI —
 * it's a profile-level preference, not a discovery filter the viewer chooses.
 */
enum class DiscoveryStyle(
    val serverValue: String,
    val label: String,
) {
    Solo("solo", "Solo"),
    Together("together", "Together"),
    OpenToBoth("both", "Open to both"),
    Social("social", "Social"),
}

/**
 * Additional intent tokens beyond `discovery_style`. Stored in `profiles.intents`
 * and overlap-matched. Spec: FILTERS_SPEC L114-138.
 */
enum class Intent(
    val serverValue: String,
    val label: String,
) {
    Dating("dating", "Dating"),
    ChatFirst("chat_first", "Chat first"),
    Events("events", "Events"),
    OpenToSingles("open_to_singles", "Open to singles"),
    OpenToCouples("open_to_couples", "Open to couples"),
    OpenToLinkedPartners("open_to_linked_partners", "Open to linked partners"),
    OpenToGroups("open_to_groups", "Open to groups"),
}

/**
 * Lifestyle tags drawn from LANGUAGE_AND_TAXONOMY_SPEC. Server values match the
 * canonical tokens stored in `profiles.lifestyle_tags` (lower_snake). A profile
 * matches when its tag array overlaps any selected tag. NULL/empty profile tag
 * arrays are excluded when the filter is active (hard "has X" semantics).
 *
 * High-sensitivity rule (FILTERS_SPEC L163): these are hidden in the sheet unless
 * the viewer is age-verified, and Ktor rejects crafted lifestyle filter payloads
 * from unverified viewers.
 */
enum class LifestyleTag(
    val serverValue: String,
    val label: String,
    val group: LifestyleGroup,
) {
    SoftSwap("soft_swap", "Soft swap", LifestyleGroup.Swap),
    FullSwap("full_swap", "Full swap", LifestyleGroup.Swap),
    SameRoom("same_room", "Same-room", LifestyleGroup.Room),
    SeparateRoom("separate_room", "Separate-room", LifestyleGroup.Room),
    NewToLifestyle("new_to_lifestyle", "New to lifestyle", LifestyleGroup.Experience),
    SomeExperience("some_experience", "Some experience", LifestyleGroup.Experience),
    Experienced("experienced", "Experienced", LifestyleGroup.Experience),
    Enm("enm", "ENM", LifestyleGroup.Other),
    Swinging("swinging", "Swinging", LifestyleGroup.Other),
    KinkFriendly("kink_friendly", "Kink-friendly", LifestyleGroup.Other),
    VanillaSocial("vanilla_social", "Vanilla/social", LifestyleGroup.Other),
    ;

    companion object {
        fun inGroup(group: LifestyleGroup): List<LifestyleTag> = entries.filter { it.group == group }

        fun fromServerValue(value: String?): LifestyleTag? = entries.firstOrNull { it.serverValue == value }
    }
}

/**
 * Lifestyle filter groups (DiscoveryTagTaxonomies): a filter matches "any of" inside a group and
 * "all of" across groups. Experience is ONE value per profile (`experienceLevel`); the others are
 * profile tags.
 */
enum class LifestyleGroup(
    val label: String,
) {
    Swap("Swap"),
    Room("Room"),
    Experience("Experience"),
    Other("Lifestyle"),
}

/**
 * Curated, consent-oriented boundaries (DiscoveryTagTaxonomies.boundaryItems). A profile marks an
 * item "ask first" or "not for me"; a filter hides people who said "not for me". Never free text.
 */
enum class Boundary(
    val serverValue: String,
    val label: String,
) {
    Photos("photos", "Photos or video"),
    SameDay("same_day", "Meeting the same day"),
    HomeMeets("home_meets", "Meeting at someone's home"),
    SeparateDates("separate_dates", "Separate dates"),
    Groups("groups", "Group settings"),
    ;

    companion object {
        fun fromServerValues(values: List<String>): Set<Boundary> = values.mapNotNull { v -> entries.firstOrNull { it.serverValue == v } }.toSet()
    }
}

/** A boundary stance a profile can take. Tokens are "<item>:<stance>" (DiscoveryTagTaxonomies). */
enum class BoundaryStance(
    val serverValue: String,
    val label: String,
) {
    AskFirst("ask_first", "Ask first"),
    NotForMe("not_for_me", "Not for me"),
    ;

    fun tokenFor(boundary: Boundary): String = "${boundary.serverValue}:$serverValue"

    companion object {
        /** Parses stored boundary tokens into item -> stance, ignoring unknown values. */
        fun parse(tokens: Collection<String>): Map<Boundary, BoundaryStance> =
            tokens
                .mapNotNull { token ->
                    val item = Boundary.entries.firstOrNull { it.serverValue == token.substringBefore(':') }
                    val stance = entries.firstOrNull { it.serverValue == token.substringAfter(':', "") }
                    if (item != null && stance != null) item to stance else null
                }.toMap()
    }
}

/**
 * Logistics tags (FILTERS_SPEC L170-184). Self-declared by the profile owner
 * and filterable as hard "has X" overlap on `profiles.logistics_tags`.
 */
enum class LogisticsTag(
    val serverValue: String,
    val label: String,
) {
    Hosts("hosts", "Hosts"),
    Travels("travels", "Travels"),
    HotelFriendly("hotel_friendly", "Hotel-friendly"),
    PublicMeetFirst("public_meet_first", "Public meet first"),
    ClubEventOnly("club_event_only", "Club/event only"),
}

@Immutable
@Serializable
data class GridProfileCardUi(
    val id: String,
    val displayName: String,
    val thumbnailUrl: String? = null,
    val online: Boolean,
    val distanceLabel: String?,
    val linkedPartner: GridLinkedPartnerUi? = null,
    val linkedPartnerTone: LinkedPartnerTone? = null,
    val distanceMeters: Int? = null,
    // SWIPE_IMPLEMENTATION_SPEC L348: target is virtually in the viewer's area
    // via passport/travel mode.
    val visiting: Boolean = false,
    // PLAYING_TONIGHT_SPEC: this individual has a live Playing Tonight session.
    val tonightActive: Boolean = false,
    // "Visiting 14–17 Nov": an announced planned trip bringing them here soon.
    val visitingDates: String? = null,
    // Photo-verified (server-owned): a small check after the name.
    val verified: Boolean = false,
    // Both this person and the partner in the mini are verified: a check on the partner mini.
    val coupleVerified: Boolean = false,
    // Server-owned Fresh rule (new account or new photo, and active lately): the tile's "New" marker.
    val fresh: Boolean = false,
    // Server presence bucket: one of [GridActiveAgo]'s values, or null when unknown/long ago.
    val activeAgo: String? = null,
    // Section 3: past the viewer's plan limit (server-decided). [id] is then an opaque "locked_"
    // token, [displayName] is empty, and the tile shows only photo, dot, distance and badges.
    val locked: Boolean = false,
    // G2-2: the server's stable key for [thumbnailUrl]'s bytes (Coil memory + disk cache key), so a
    // re-signed URL is a cache hit. Names bytes only; the URL still guards access. Null when locked.
    val thumbnailCacheKey: String? = null,
)

/**
 * The server's coarse "last active" buckets on a Grid card. Coarse on purpose: a bucket never lets
 * anyone time a member's comings and goings to the minute.
 */
object GridActiveAgo {
    const val ONLINE: String = "online"
    const val MINUTES_15: String = "15m"
    const val HOUR_1: String = "1h"
    const val HOURS_3: String = "3h"
    const val TODAY: String = "today"
    const val WEEK: String = "week"

    /** The small tile caption for someone not online now, or null when there is nothing to say. */
    fun label(activeAgo: String?): String? =
        when (activeAgo) {
            MINUTES_15 -> "Active 15m ago"
            HOUR_1 -> "Active 1h ago"
            HOURS_3 -> "Active 3h ago"
            TODAY -> "Active today"
            WEEK -> "Active this week"
            else -> null
        }
}

/**
 * A card painted from the cold-start cache: its presence is days old, so it shows as unknown (no
 * green dot, no "active" caption, no New marker) until the first live answer replaces it.
 */
internal fun GridProfileCardUi.withPresenceUnknown(): GridProfileCardUi = copy(online = false, activeAgo = null, fresh = false)

@Immutable
@Serializable
data class GridLinkedPartnerUi(
    val userId: String,
    val displayName: String? = null,
    val thumbnailUrl: String? = null,
    // The partner's own declared age and gender key (plan G2-3). The server sends them only with a
    // mini it shows this viewer; the tile draws [partnerMiniCaption] under the mini.
    val age: Int? = null,
    val gender: String? = null,
    val thumbnailCacheKey: String? = null,
)

/**
 * The small caption under a tile's partner mini: the partner's age and a short gender mark, "34 · W".
 * Only the partner's own declared values; never a couple label (no "MF"/"FF"), never inferred.
 * Self-describe and unknown keys get no mark. Null when there is nothing to show.
 */
internal fun partnerMiniCaption(partner: GridLinkedPartnerUi?): String? {
    if (partner == null) return null
    val mark = partner.gender?.let { PARTNER_GENDER_MARKS[it] }
    val parts = listOfNotNull(partner.age?.toString(), mark)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Screen-reader text for the partner mini: "Linked partner, 34, woman". */
internal fun partnerMiniDescription(partner: GridLinkedPartnerUi): String {
    val gender =
        partner.gender
            ?.takeIf { it in PARTNER_GENDER_MARKS }
            ?.let {
                com.pynapli.shared.profile.ProfileTaxonomies
                    .genderLabel(it)
                    ?.lowercase()
            }
    return listOfNotNull("Linked partner", partner.age?.toString(), gender).joinToString(", ")
}

// Short marks for the structured gender keys (ProfileTaxonomies). Self-describe has none.
private val PARTNER_GENDER_MARKS: Map<String, String> =
    mapOf(
        "woman" to "W",
        "man" to "M",
        "trans_woman" to "TW",
        "trans_man" to "TM",
        "non_binary" to "NB",
    )

@Serializable
enum class LinkedPartnerTone {
    Violet,
    Gold,
    Pink,
    Teal,
    Sun,
}

internal fun GridCardDto.toGridProfileCardUi(index: Int): GridProfileCardUi {
    val isLocked = locked || userId.startsWith(LOCKED_CARD_ID_PREFIX)
    // A locked card carries no identity. The server already strips it; dropping the name, the
    // activity bucket and the partner mini (a real partner id) here too keeps a server slip from
    // ever reaching a tile, a cache or a screen reader.
    val partner = linkedPartner?.takeUnless { isLocked }
    return GridProfileCardUi(
        id = userId,
        displayName = if (isLocked) "" else displayName,
        thumbnailUrl = thumbnailUrl,
        online = online,
        distanceLabel = distanceLabel,
        linkedPartner = partner?.toUi(),
        linkedPartnerTone = partner?.let { LinkedPartnerTone.entries[index % LinkedPartnerTone.entries.size] },
        distanceMeters = distanceMeters,
        visiting = visiting,
        tonightActive = tonightActive,
        visitingDates =
            visitingFrom?.let { from ->
                com.pynapli.app.swipe
                    .tripDateRange(from, visitingUntil ?: from)
            },
        verified = verified,
        // Server-computed; never drawn without a visible partner mini to attach it to.
        coupleVerified = coupleVerified && partner != null,
        fresh = fresh,
        activeAgo = if (isLocked) null else activeAgo,
        locked = isLocked,
        // A locked tile is cached by URL only: no stable identifier reaches it (Section 3).
        thumbnailCacheKey = if (isLocked) null else thumbnailCacheKey,
    )
}

internal fun LinkedPartnerMiniDto.toUi(): GridLinkedPartnerUi =
    GridLinkedPartnerUi(
        userId = userId,
        displayName = displayName,
        thumbnailUrl = thumbnailUrl,
        age = age,
        gender = gender,
        thumbnailCacheKey = thumbnailCacheKey,
    )
