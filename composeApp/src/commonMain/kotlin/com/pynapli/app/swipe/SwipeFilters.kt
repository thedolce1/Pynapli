package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable
import com.pynapli.app.grid.DiscoveryStyle
import com.pynapli.app.grid.Intent
import com.pynapli.app.grid.LifestyleTag
import com.pynapli.app.grid.LogisticsTag
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto

/**
 * Canonical client-side filter state for the swipe deck.
 *
 * FILTERS_SPEC L263-415 frames Swipe filters as strategic and mostly soft, with
 * per-field dealbreaker toggles. This data class carries the full filter surface
 * exposed by SwipeDeckRequestDto plus the per-field dealbreaker set and the soft
 * `verifiedPreferred` ranking signal.
 *
 * Discovery taxonomy enums (DiscoveryStyle / Intent / LifestyleTag / LogisticsTag)
 * are reused from the Grid package because their canonical server tokens are shared
 * across discovery surfaces. Filters that Spec L13 reserves for the live Grid
 * directory (onlineOnly, tonightAvailableOnly) are intentionally excluded — Swipe
 * is for intent, Grid is for now.
 */
@Immutable
data class SwipeFilters(
    val singles: Boolean = false,
    val partnered: Boolean = false,
    val linked: Boolean = false,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val minimumAge: Int? = null,
    val maximumAge: Int? = null,
    val verifiedOnly: Boolean = false,
    val verifiedPreferred: Boolean = false,
    val facePhotoVerified: Boolean = false,
    val ageVerified: Boolean = false,
    val discoveryStyles: Set<DiscoveryStyle> = emptySet(),
    val intents: Set<Intent> = emptySet(),
    val lifestyleTags: Set<LifestyleTag> = emptySet(),
    val logisticsTags: Set<LogisticsTag> = emptySet(),
    // Structured gender/orientation keys from ProfileTaxonomies (self-describe excluded).
    val genders: Set<String> = emptySet(),
    val orientations: Set<String> = emptySet(),
    val dealbreakers: Set<String> = emptySet(),
) {
    val activeCount: Int
        get() {
            var count = 0
            if (singles) count += 1
            if (partnered) count += 1
            if (linked) count += 1
            if (radiusMeters != DEFAULT_RADIUS_METERS) count += 1
            if (minimumAge != null || maximumAge != null) count += 1
            if (verifiedOnly) count += 1
            if (verifiedPreferred && !verifiedOnly) count += 1
            if (facePhotoVerified) count += 1
            if (ageVerified) count += 1
            if (discoveryStyles.isNotEmpty()) count += 1
            if (intents.isNotEmpty()) count += 1
            if (lifestyleTags.isNotEmpty()) count += 1
            if (logisticsTags.isNotEmpty()) count += 1
            if (genders.isNotEmpty()) count += 1
            if (orientations.isNotEmpty()) count += 1
            return count
        }

    val isAnyActive: Boolean get() = activeCount > 0

    /**
     * Mirrors `DiscoveryFilters.requiresAdvancedEntitlement` server-side. Used by the
     * client to surface a paywall hint before issuing a request that the server would
     * reject with `ENTITLEMENT_REQUIRED`.
     */
    val requiresAdvancedEntitlement: Boolean
        get() =
            lifestyleTags.isNotEmpty() ||
                logisticsTags.any { it != LogisticsTag.PublicMeetFirst }

    fun toSwipeDeckRequest(cursor: String? = null): SwipeDeckRequestDto {
        val contexts =
            buildList {
                if (singles) add(SINGLES_CONTEXT)
                if (partnered) add(PARTNERED_CONTEXT)
            }
        return SwipeDeckRequestDto(
            radiusMeters = radiusMeters,
            limit = PAGE_SIZE,
            cursor = cursor,
            minimumAge = minimumAge,
            maximumAge = maximumAge,
            relationshipContexts = contexts,
            linkedOnly = if (linked) true else null,
            verifiedOnly = verifiedOnly,
            facePhotoVerifiedOnly = facePhotoVerified,
            ageVerifiedOnly = ageVerified,
            discoveryStyles = discoveryStyles.map { it.serverValue },
            intents = intents.map { it.serverValue },
            lifestyleTags = lifestyleTags.map { it.serverValue },
            logisticsTags = logisticsTags.map { it.serverValue },
            genders = genders.toList(),
            orientations = orientations.toList(),
            dealbreakers = dealbreakers,
            verifiedPreferred = verifiedPreferred,
        )
    }

    companion object {
        const val DEFAULT_RADIUS_METERS: Int = 25_000
        const val MIN_RADIUS_METERS: Int = 1_000
        const val MAX_RADIUS_METERS: Int = 100_000
        const val MIN_FILTER_AGE: Int = 18
        const val MAX_FILTER_AGE: Int = 99
        const val PAGE_SIZE: Int = 30
        const val SINGLES_CONTEXT: String = "single"
        const val PARTNERED_CONTEXT: String = "partnered"

        val defaults: SwipeFilters = SwipeFilters()

        // Quick chip presets the screen currently exposes. The sheet (Phase C2+)
        // will mutate SwipeFilters directly; presets stay as one-tap shortcuts.
        val forYou: SwipeFilters = defaults
        val linkedOnly: SwipeFilters = defaults.copy(linked = true)
        val ageRange: SwipeFilters = defaults.copy(minimumAge = 30, maximumAge = 55)

        /**
         * Inverse of `toSwipeDeckRequest`. Unknown discovery_style / intent /
         * lifestyle / logistics tokens are dropped silently so older payloads can be
         * partially applied after a taxonomy change. The server re-validates on the
         * next `/v1/swipe/deck/query` regardless.
         */
        fun fromSwipeDeckRequest(request: SwipeDeckRequestDto): SwipeFilters {
            val contexts = request.relationshipContexts.toSet()
            val styleByValue = DiscoveryStyle.entries.associateBy { it.serverValue }
            val lifestyleByValue = LifestyleTag.entries.associateBy { it.serverValue }
            val logisticsByValue = LogisticsTag.entries.associateBy { it.serverValue }
            val intentByValue = Intent.entries.associateBy { it.serverValue }
            val dealbreakers =
                request.dealbreakers
                    .filter { it in SwipeDealbreakerKey.ALL }
                    .toSet()
            return SwipeFilters(
                singles = SINGLES_CONTEXT in contexts,
                partnered = PARTNERED_CONTEXT in contexts,
                linked = request.linkedOnly == true,
                radiusMeters = request.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS),
                minimumAge = request.minimumAge?.coerceIn(MIN_FILTER_AGE, MAX_FILTER_AGE),
                maximumAge = request.maximumAge?.coerceIn(MIN_FILTER_AGE, MAX_FILTER_AGE),
                verifiedOnly = request.verifiedOnly,
                verifiedPreferred = request.verifiedPreferred,
                facePhotoVerified = request.facePhotoVerifiedOnly,
                ageVerified = request.ageVerifiedOnly,
                discoveryStyles = request.discoveryStyles.mapNotNull { styleByValue[it] }.toSet(),
                intents = request.intents.mapNotNull { intentByValue[it] }.toSet(),
                lifestyleTags = request.lifestyleTags.mapNotNull { lifestyleByValue[it] }.toSet(),
                logisticsTags = request.logisticsTags.mapNotNull { logisticsByValue[it] }.toSet(),
                genders = request.genders.toSet(),
                orientations = request.orientations.toSet(),
                dealbreakers = dealbreakers,
            )
        }
    }
}
