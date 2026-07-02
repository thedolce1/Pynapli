package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import kotlinx.serialization.Serializable

@Immutable
data class GridUiState(
    val locationLabel: String = "Current location",
    val filters: GridFilters = GridFilters.defaults,
    val cards: List<GridProfileCardUi> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val paywallMessage: String? = null,
    // Set when the server rejects a gated action with AGE_VERIFICATION_REQUIRED. Drives the
    // "Verify your age" prompt row; its CTA routes into the Profile-Hub age-verification flow.
    // Mirrors [paywallMessage]'s ENTITLEMENT_REQUIRED handling.
    val verificationMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val verificationRequired: Boolean get() = verificationMessage != null
}

@Immutable
data class GridFilters(
    val online: Boolean = false,
    val singles: Boolean = false,
    val partnered: Boolean = false,
    val linked: Boolean = false,
    val verified: Boolean = false,
    val facePhotoVerified: Boolean = false,
    val ageVerified: Boolean = false,
    val discoveryStyles: Set<DiscoveryStyle> = emptySet(),
    val lifestyleTags: Set<LifestyleTag> = emptySet(),
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
    // FILTERS_SPEC "Recently joined" / "Traveling here" (Presence, grid-only).
    val recentlyJoined: Boolean = false,
    val travelingHere: Boolean = false,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
) {
    val activeCount: Int
        get() {
            var count = 0
            if (online) count += 1
            if (singles) count += 1
            if (partnered) count += 1
            if (linked) count += 1
            if (verified) count += 1
            if (facePhotoVerified) count += 1
            if (ageVerified) count += 1
            if (discoveryStyles.isNotEmpty()) count += 1
            if (lifestyleTags.isNotEmpty()) count += 1
            if (logisticsTags.isNotEmpty()) count += 1
            if (intents.isNotEmpty()) count += 1
            if (genders.isNotEmpty()) count += 1
            if (orientations.isNotEmpty()) count += 1
            if (withPhotos) count += 1
            if (hasPrivateAlbum) count += 1
            if (tonightAvailable) count += 1
            if (activeToday) count += 1
            if (recentlyJoined) count += 1
            if (travelingHere) count += 1
            if (radiusMeters != DEFAULT_RADIUS_METERS) count += 1
            if (minimumAge != null || maximumAge != null) count += 1
            return count
        }

    val isAnyActive: Boolean get() = activeCount > 0

    val requiresAdvancedEntitlement: Boolean
        get() =
            lifestyleTags.isNotEmpty() ||
                logisticsTags.any { it != LogisticsTag.PublicMeetFirst }

    fun toGridQueryRequest(cursor: String?): GridQueryRequestDto {
        val contexts =
            buildList {
                if (singles) add(SINGLES_CONTEXT)
                if (partnered) add(PARTNERED_CONTEXT)
            }
        return GridQueryRequestDto(
            radiusMeters = radiusMeters,
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
            logisticsTags = logisticsTags.map { it.serverValue },
            tonightAvailableOnly = tonightAvailable,
            activeTodayOnly = activeToday,
            recentlyJoinedOnly = recentlyJoined,
            travelingHereOnly = travelingHere,
            withPhotosOnly = withPhotos,
            hasPrivateAlbumOnly = hasPrivateAlbum,
            intents = intents.map { it.serverValue },
            genders = genders.toList(),
            orientations = orientations.toList(),
        )
    }

    companion object {
        const val DEFAULT_RADIUS_METERS: Int = 50_000
        const val MIN_RADIUS_METERS: Int = 1_000
        const val MAX_RADIUS_METERS: Int = 100_000
        const val MIN_FILTER_AGE: Int = 18
        const val MAX_FILTER_AGE: Int = 99
        const val PAGE_SIZE: Int = 60
        const val SINGLES_CONTEXT: String = "single"
        const val PARTNERED_CONTEXT: String = "partnered"
        val defaults: GridFilters = GridFilters()

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
                singles = SINGLES_CONTEXT in contexts,
                partnered = PARTNERED_CONTEXT in contexts,
                linked = request.linkedOnly == true,
                verified = request.verifiedOnly,
                facePhotoVerified = request.facePhotoVerifiedOnly,
                ageVerified = request.ageVerifiedOnly,
                discoveryStyles = request.discoveryStyles.mapNotNull { styleByValue[it] }.toSet(),
                lifestyleTags = request.lifestyleTags.mapNotNull { lifestyleByValue[it] }.toSet(),
                logisticsTags = request.logisticsTags.mapNotNull { logisticsByValue[it] }.toSet(),
                intents = request.intents.mapNotNull { intentByValue[it] }.toSet(),
                genders = request.genders.toSet(),
                orientations = request.orientations.toSet(),
                withPhotos = request.withPhotosOnly,
                hasPrivateAlbum = request.hasPrivateAlbumOnly,
                tonightAvailable = request.tonightAvailableOnly,
                activeToday = request.activeTodayOnly,
                recentlyJoined = request.recentlyJoinedOnly,
                travelingHere = request.travelingHereOnly,
                radiusMeters = request.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS),
                minimumAge = request.minimumAge?.coerceIn(MIN_FILTER_AGE, MAX_FILTER_AGE),
                maximumAge = request.maximumAge?.coerceIn(MIN_FILTER_AGE, MAX_FILTER_AGE),
            )
        }
    }
}

/**
 * Identifies a quick-toggle chip exposed in the grid header. Chips that require backing
 * features land in their own slices (Verified in F2, Together in F3, Tonight in F6) and
 * are not modeled here yet.
 */
enum class QuickFilterId {
    Online,
    Singles,
    Linked,
    Verified,
    Tonight,
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
) {
    SoftSwap("soft_swap", "Soft swap"),
    FullSwap("full_swap", "Full swap"),
    SameRoom("same_room", "Same-room"),
    SeparateRoom("separate_room", "Separate-room"),
    NewToLifestyle("new_to_lifestyle", "New"),
    Experienced("experienced", "Experienced"),
    KinkFriendly("kink_friendly", "Kink-friendly"),
    VanillaSocial("vanilla_social", "Vanilla/social"),
    Enm("enm", "ENM"),
    Swinging("swinging", "Swinging"),
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
    val tone: GridCardTone,
    val linkedPartner: GridLinkedPartnerUi? = null,
    val linkedPartnerTone: LinkedPartnerTone? = null,
    val distanceMeters: Int? = null,
    // SWIPE_IMPLEMENTATION_SPEC L348: target is virtually in the viewer's area
    // via passport/travel mode.
    val visiting: Boolean = false,
    // PLAYING_TONIGHT_SPEC: this individual has a live Playing Tonight session.
    val tonightActive: Boolean = false,
)

@Immutable
@Serializable
data class GridLinkedPartnerUi(
    val userId: String,
    val displayName: String? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
enum class GridCardTone {
    Warm,
    Slate,
    Rust,
    DeepGreen,
    Bronze,
    Graphite,
    Teal,
    Rose,
    Cocoa,
    Moss,
    Stone,
    Midnight,
}

@Serializable
enum class LinkedPartnerTone {
    Violet,
    Gold,
    Pink,
    Teal,
    Sun,
}

internal fun GridCardDto.toGridProfileCardUi(index: Int): GridProfileCardUi =
    GridProfileCardUi(
        id = userId,
        displayName = displayName,
        thumbnailUrl = thumbnailUrl,
        online = online,
        distanceLabel = distanceLabel,
        tone = GridCardTone.entries[index % GridCardTone.entries.size],
        linkedPartner = linkedPartner?.toUi(),
        linkedPartnerTone = linkedPartner?.let { LinkedPartnerTone.entries[index % LinkedPartnerTone.entries.size] },
        distanceMeters = distanceMeters,
        visiting = visiting,
        tonightActive = tonightActive,
    )

internal fun LinkedPartnerMiniDto.toUi(): GridLinkedPartnerUi =
    GridLinkedPartnerUi(
        userId = userId,
        displayName = displayName,
        thumbnailUrl = thumbnailUrl,
    )
