package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.profile.DiscoveryTagTaxonomies

internal object SwipeDeckRelaxation {
    // Below this, the deck is considered "low" and relaxation is attempted on
    // non-dealbreaker filters (FILTERS_SPEC L373 + L403-409). Threshold is the
    // larger of limit/3 and MIN_RELAXATION_THRESHOLD so micro-relaxations on
    // very small page requests still fire when the deck is essentially empty.
    private const val MIN_RELAXATION_THRESHOLD = 3

    fun lowWaterMark(limit: Int): Int = (limit / 3).coerceAtLeast(MIN_RELAXATION_THRESHOLD)

    fun hasRelaxableFilters(
        filters: DiscoveryFilters,
        radiusMeters: Int,
        maxRadiusMeters: Int,
        dealbreakers: Set<String>,
    ): Boolean {
        if (SwipeDealbreakerKey.AGE !in dealbreakers &&
            (filters.minimumAge != null || filters.maximumAge != null)
        ) {
            return true
        }
        if (SwipeDealbreakerKey.VERIFIED !in dealbreakers &&
            (filters.verifiedOnly || filters.verifiedCouplesOnly || filters.facePhotoVerifiedOnly || filters.ageVerifiedOnly)
        ) {
            return true
        }
        if (SwipeDealbreakerKey.DISCOVERY_STYLES !in dealbreakers &&
            filters.discoveryStyles.isNotEmpty()
        ) {
            return true
        }
        if (SwipeDealbreakerKey.INTENTS !in dealbreakers &&
            filters.intents.isNotEmpty()
        ) {
            return true
        }
        if (SwipeDealbreakerKey.LINKED_ONLY !in dealbreakers &&
            filters.linkedOnly != null
        ) {
            return true
        }
        // relationshipContexts, logisticsTags and the non-swinger lifestyle tags are always
        // relaxable; swap / room / experience and boundaries only when not a dealbreaker.
        if (filters.relationshipContexts.isNotEmpty()) return true
        if (relaxLifestyleTags(filters.lifestyleTags, dealbreakers) != filters.lifestyleTags) return true
        if (SwipeDealbreakerKey.BOUNDARIES !in dealbreakers && filters.boundaryItems.isNotEmpty()) return true
        if (filters.logisticsTags.isNotEmpty()) return true
        if (SwipeDealbreakerKey.DISTANCE !in dealbreakers &&
            radiusMeters < maxRadiusMeters
        ) {
            return true
        }
        return false
    }

    fun relaxFilters(
        filters: DiscoveryFilters,
        dealbreakers: Set<String>,
    ): DiscoveryFilters =
        filters.copy(
            minimumAge = if (SwipeDealbreakerKey.AGE in dealbreakers) filters.minimumAge else null,
            maximumAge = if (SwipeDealbreakerKey.AGE in dealbreakers) filters.maximumAge else null,
            relationshipContexts = emptySet(),
            linkedOnly = if (SwipeDealbreakerKey.LINKED_ONLY in dealbreakers) filters.linkedOnly else null,
            verifiedOnly = if (SwipeDealbreakerKey.VERIFIED in dealbreakers) filters.verifiedOnly else false,
            // Same "verified" lock as verifiedOnly: kept when locked, relaxed otherwise.
            verifiedCouplesOnly = if (SwipeDealbreakerKey.VERIFIED in dealbreakers) filters.verifiedCouplesOnly else false,
            facePhotoVerifiedOnly = if (SwipeDealbreakerKey.VERIFIED in dealbreakers) filters.facePhotoVerifiedOnly else false,
            ageVerifiedOnly = if (SwipeDealbreakerKey.VERIFIED in dealbreakers) filters.ageVerifiedOnly else false,
            discoveryStyles =
                if (SwipeDealbreakerKey.DISCOVERY_STYLES in dealbreakers) {
                    filters.discoveryStyles
                } else {
                    emptySet()
                },
            intents = if (SwipeDealbreakerKey.INTENTS in dealbreakers) filters.intents else emptySet(),
            lifestyleTags = relaxLifestyleTags(filters.lifestyleTags, dealbreakers),
            boundaryItems = if (SwipeDealbreakerKey.BOUNDARIES in dealbreakers) filters.boundaryItems else emptySet(),
            logisticsTags = emptySet(),
        )

    /** Keeps only the lifestyle groups the viewer marked as dealbreakers. */
    private fun relaxLifestyleTags(
        tags: Set<String>,
        dealbreakers: Set<String>,
    ): Set<String> {
        val groups = DiscoveryTagTaxonomies.lifestyleFilterGroups(tags)
        return buildSet {
            if (SwipeDealbreakerKey.SWAP in dealbreakers) addAll(groups.swap)
            if (SwipeDealbreakerKey.ROOM in dealbreakers) addAll(groups.room)
            if (SwipeDealbreakerKey.EXPERIENCE in dealbreakers) addAll(groups.experience)
        }
    }

    fun relaxRadius(
        radiusMeters: Int,
        maxRadiusMeters: Int,
        dealbreakers: Set<String>,
    ): Int =
        if (SwipeDealbreakerKey.DISTANCE in dealbreakers) {
            radiusMeters
        } else {
            (radiusMeters * 2).coerceAtMost(maxRadiusMeters)
        }
}
