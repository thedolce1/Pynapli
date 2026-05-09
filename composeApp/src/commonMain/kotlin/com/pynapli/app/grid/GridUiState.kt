package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto

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
    val errorCode: String? = null,
    val errorMessage: String? = null
)

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
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val minimumAge: Int? = null,
    val maximumAge: Int? = null
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
            if (radiusMeters != DEFAULT_RADIUS_METERS) count += 1
            if (minimumAge != null || maximumAge != null) count += 1
            return count
        }

    val isAnyActive: Boolean get() = activeCount > 0

    fun toGridQueryRequest(cursor: String?): GridQueryRequestDto {
        val contexts = buildList {
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
            discoveryStyles = discoveryStyles.map { it.serverValue }
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
    Verified
}

/**
 * Mirrors the values allowed by the `profiles.discovery_style` CHECK constraint
 * (V3 migration). Ask-in-chat is intentionally omitted from the grid filter UI —
 * it's a profile-level preference, not a discovery filter the viewer chooses.
 */
enum class DiscoveryStyle(val serverValue: String, val label: String) {
    Solo("solo", "Solo"),
    Together("together", "Together"),
    OpenToBoth("both", "Open to both"),
    Social("social", "Social")
}

@Immutable
data class GridProfileCardUi(
    val id: String,
    val displayName: String,
    val thumbnailUrl: String? = null,
    val online: Boolean,
    val distanceLabel: String?,
    val tone: GridCardTone,
    val linkedPartner: GridLinkedPartnerUi? = null,
    val linkedPartnerTone: LinkedPartnerTone? = null,
    val distanceMeters: Int? = null
)

@Immutable
data class GridLinkedPartnerUi(
    val userId: String,
    val displayName: String? = null,
    val thumbnailUrl: String? = null
)

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
    Midnight
}

enum class LinkedPartnerTone {
    Violet,
    Gold,
    Pink,
    Teal,
    Sun
}

internal fun GridCardDto.toGridProfileCardUi(index: Int): GridProfileCardUi = GridProfileCardUi(
    id = userId,
    displayName = displayName,
    thumbnailUrl = thumbnailUrl,
    online = online,
    distanceLabel = distanceLabel,
    tone = GridCardTone.entries[index % GridCardTone.entries.size],
    linkedPartner = linkedPartner?.toUi(),
    linkedPartnerTone = linkedPartner?.let { LinkedPartnerTone.entries[index % LinkedPartnerTone.entries.size] },
    distanceMeters = distanceMeters
)

internal fun LinkedPartnerMiniDto.toUi(): GridLinkedPartnerUi = GridLinkedPartnerUi(
    userId = userId,
    displayName = displayName,
    thumbnailUrl = thumbnailUrl
)
