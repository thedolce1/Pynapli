package com.pynapli.server.tonight

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.distance.CoarseLocation
import com.pynapli.server.grid.GridViewerTrustRepository
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.TonightFeedItemDto
import com.pynapli.shared.network.dto.TonightFeedPersonDto
import com.pynapli.shared.network.dto.TonightFeedResponseDto
import java.time.Clock
import java.util.UUID

/**
 * Playing Tonight feed authority (PT-3, PLAYING_TONIGHT_SPEC). A near-viewer list of
 * LIVE sessions, one row per session anchored on the owner with the linked partner as
 * a mini (never a couple card). The repository runs the spatial discovery query; this
 * service enforces every public-exposure rule the Grid enforces:
 *  - drop a row whose OWNER is blocked or hidden by the viewer;
 *  - keep a row but null the partner mini when the PARTNER is blocked or hidden;
 *  - distance label/meters only when the owner opted into showing distance;
 *  - zone-only location (a 0.02-degree zone of the owner's effective location, omitted when
 *    the owner hides distance) — never precise coordinates;
 *  - online resolved from presence; thumbnails resolved to signed/public URLs.
 */
class TonightFeedService(
    private val repository: TonightFeedRepository,
    private val presenceService: PresenceService? = null,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null,
    private val hiddenProfileRepository: HiddenProfileRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
    // Same gates the Grid applies to lifestyle filters, so Tonight is never a bypass.
    private val viewerTrustRepository: GridViewerTrustRepository? = null,
    private val subscriptionService: SubscriptionService? = null,
) {
    suspend fun feed(
        viewerUserId: UUID,
        radiusMeters: Int,
        intent: String?,
        limit: Int,
        orientations: List<String> = emptyList(),
        lifestyleTags: List<String> = emptyList(),
        boundaryItems: List<String> = emptyList(),
    ): TonightFeedResponseDto {
        val normalizedIntent = intent?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedIntent != null && normalizedIntent !in VALID_INTENTS) {
            throw TonightSessionException("INVALID_TONIGHT_INTENT")
        }
        // Round 4: the owner's own orientation, swap/room/experience and boundaries, matched by the
        // same predicates as Grid and Swipe (LifestyleFilterSql). One-directional; never logged.
        val filters =
            discoveryFiltersFrom(
                minimumAge = null,
                maximumAge = null,
                relationshipContexts = emptyList(),
                linkedOnly = null,
                orientations = orientations,
                lifestyleTags = lifestyleTags,
                boundaryItems = boundaryItems,
            ) { TonightSessionException("INVALID_TONIGHT_FILTER") }
        requireFilterAccess(viewerUserId, filters)

        // L-09: snapped to the same fixed radius steps as the Grid.
        val boundedRadius = CoarseLocation.snapRadiusMeters(radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS))
        val boundedLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)

        val rows =
            repository.feedNear(
                viewerUserId = viewerUserId,
                radiusMeters = boundedRadius,
                intent = normalizedIntent,
                limit = boundedLimit,
                now = clock.instant(),
                filters = filters,
            )
        if (rows.isEmpty()) return TonightFeedResponseDto(items = emptyList())

        // Per-viewer identity exclusions, applied after the repository (same split as
        // GridService): a blocked/hidden OWNER drops the row; a blocked/hidden PARTNER
        // only suppresses the mini.
        val excludedIds =
            blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty() +
                hiddenProfileRepository?.listHiddenProfileIds(viewerUserId).orEmpty()

        val visibleRows = rows.filterNot { it.ownerUserId in excludedIds }
        if (visibleRows.isEmpty()) return TonightFeedResponseDto(items = emptyList())

        // Resolve online for every surfaced owner + (non-excluded) partner in one call.
        val presenceIds =
            buildSet {
                visibleRows.forEach { row ->
                    add(row.ownerUserId)
                    row.partnerUserId?.takeIf { it !in excludedIds }?.let { add(it) }
                }
            }
        val onlineIds = onlineUserIdsFor(presenceIds)

        val items =
            visibleRows.map { row ->
                val partnerVisible = row.partnerUserId != null && row.partnerUserId !in excludedIds
                TonightFeedItemDto(
                    sessionId = row.sessionId.toString(),
                    mode = row.mode,
                    intent = row.intent,
                    hostMode = row.hostMode,
                    note = row.note,
                    photoUrl = row.photoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                    person =
                        TonightFeedPersonDto(
                            userId = row.ownerUserId.toString(),
                            displayName = row.ownerDisplayName,
                            thumbnailUrl =
                                row.ownerThumbnailMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                            online = row.ownerUserId in onlineIds,
                            distanceLabel =
                                if (row.ownerShowDistance) row.ownerDistanceMeters.toPublicDistanceLabel() else null,
                            distanceMeters =
                                if (row.ownerShowDistance) row.ownerDistanceMeters.toPublicDistanceMeters() else null,
                            // L-11: the zone is location too; an owner who hides distance gets none.
                            zoneKey = if (row.ownerShowDistance) "${row.ownerZoneX}_${row.ownerZoneY}" else null,
                        ),
                    linkedPartner =
                        if (partnerVisible) {
                            LinkedPartnerMiniDto(
                                userId = row.partnerUserId.toString(),
                                displayName = row.partnerDisplayName,
                                thumbnailUrl =
                                    row.partnerThumbnailMediaAssetId
                                        ?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                                online = row.partnerUserId in onlineIds,
                            )
                        } else {
                            null
                        },
                )
            }

        return TonightFeedResponseDto(items = items)
    }

    private suspend fun requireFilterAccess(
        viewerUserId: UUID,
        filters: DiscoveryFilters,
    ) {
        if (filters.lifestyleTags.isEmpty()) return
        val ageVerified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
        if (!ageVerified) throw TonightSessionException("AGE_VERIFICATION_REQUIRED")
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        if (filters.requiresAdvancedEntitlement && !policy.advancedFiltersAllowed) {
            throw TonightSessionException("ENTITLEMENT_REQUIRED")
        }
    }

    private suspend fun onlineUserIdsFor(userIds: Set<UUID>): Set<UUID> = runCatching { presenceService?.onlineUserIds(userIds).orEmpty() }.getOrDefault(emptySet())

    private companion object {
        const val MIN_RADIUS_METERS = 1_000
        const val MAX_RADIUS_METERS = 100_000
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 200
        val VALID_INTENTS = setOf("solo", "together", "social", "event")
    }
}
