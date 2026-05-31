package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.grid.GridViewerTrustRepository
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SavedSwipeFiltersListDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Saved swipe filter presets (FILTERS_SPEC L463-477). Mirrors `SavedGridFilterService`
 * so the two presets surfaces have matching semantics — entitlement gate, payload
 * re-validation, age-verified gate for lifestyle tags, name/cap rules. Payload is
 * the verbatim `SwipeDeckRequestDto` minus cursor (request-time only); validation
 * runs through the same `discoveryFiltersFrom` and `SwipeDealbreakerKey.ALL` checks
 * the live `SwipeService.queryDeck` uses, so a future taxonomy or dealbreaker change
 * automatically rejects stale presets on apply.
 */
class SavedSwipeFilterService(
    private val repository: SavedSwipeFilterRepository,
    private val subscriptionService: SubscriptionService? = null,
    private val viewerTrustRepository: GridViewerTrustRepository? = null,
    private val json: Json = Json,
    private val idFactory: () -> UUID = UUID::randomUUID,
) {
    suspend fun list(viewerUserId: UUID): SavedSwipeFiltersListDto {
        requireEntitlement(viewerUserId)
        return SavedSwipeFiltersListDto(
            items = repository.list(viewerUserId).map { it.toDto() },
        )
    }

    suspend fun create(
        viewerUserId: UUID,
        request: CreateSavedSwipeFilterRequestDto,
    ): SavedSwipeFilterDto {
        requireEntitlement(viewerUserId)
        val name = request.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw SavedSwipeFilterException("INVALID_SAVED_FILTER_NAME")
        }
        validateDealbreakers(request.payload)
        val filters = validatePayload(request.payload)
        requireLifestyleFilterAccess(viewerUserId, filters)
        if (repository.countForUser(viewerUserId) >= MAX_PRESETS_PER_USER) {
            throw SavedSwipeFilterException("SAVED_FILTER_LIMIT_REACHED")
        }
        val payloadJson =
            json.encodeToString(
                SwipeDeckRequestDto.serializer(),
                request.payload.normalized(),
            )
        val record =
            try {
                repository.create(
                    id = idFactory(),
                    userId = viewerUserId,
                    name = name,
                    payloadJson = payloadJson,
                )
            } catch (_: SavedSwipeFilterDuplicateNameException) {
                throw SavedSwipeFilterException("SAVED_FILTER_NAME_TAKEN")
            }
        return record.toDto()
    }

    suspend fun delete(
        viewerUserId: UUID,
        idText: String,
    ): Boolean {
        requireEntitlement(viewerUserId)
        val id =
            runCatching { UUID.fromString(idText) }
                .getOrElse { throw SavedSwipeFilterException("INVALID_SAVED_FILTER_ID") }
        return repository.delete(viewerUserId, id)
    }

    private suspend fun requireEntitlement(viewerUserId: UUID) {
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        if (!policy.advancedFiltersAllowed) {
            throw SavedSwipeFilterException("ENTITLEMENT_REQUIRED")
        }
    }

    private fun validateDealbreakers(payload: SwipeDeckRequestDto) {
        payload.dealbreakers.forEach { key ->
            if (key.trim() !in SwipeDealbreakerKey.ALL) {
                throw SavedSwipeFilterException("INVALID_SAVED_FILTER_PAYLOAD")
            }
        }
    }

    private fun validatePayload(payload: SwipeDeckRequestDto): DiscoveryFilters =
        discoveryFiltersFrom(
            minimumAge = payload.minimumAge,
            maximumAge = payload.maximumAge,
            relationshipContexts = payload.relationshipContexts,
            linkedOnly = payload.linkedOnly,
            verifiedOnly = payload.verifiedOnly,
            facePhotoVerifiedOnly = payload.facePhotoVerifiedOnly,
            ageVerifiedOnly = payload.ageVerifiedOnly,
            discoveryStyles = payload.discoveryStyles,
            lifestyleTags = payload.lifestyleTags,
            logisticsTags = payload.logisticsTags,
            intents = payload.intents,
            verifiedPreferred = payload.verifiedPreferred,
        ) { _ -> SavedSwipeFilterException("INVALID_SAVED_FILTER_PAYLOAD") }

    private suspend fun requireLifestyleFilterAccess(
        viewerUserId: UUID,
        filters: DiscoveryFilters,
    ) {
        if (filters.lifestyleTags.isEmpty()) return
        val verified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
        if (!verified) throw SavedSwipeFilterException("AGE_VERIFICATION_REQUIRED")
    }

    /**
     * Strips request-time fields that should never be persisted in a preset.
     * Cursor is request-time only; limit is the server default for the preset's
     * fresh deck session.
     */
    private fun SwipeDeckRequestDto.normalized(): SwipeDeckRequestDto = copy(cursor = null)

    private fun SavedSwipeFilterRecord.toDto(): SavedSwipeFilterDto =
        SavedSwipeFilterDto(
            id = id.toString(),
            name = name,
            payload = json.decodeFromString(SwipeDeckRequestDto.serializer(), payloadJson),
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    companion object {
        const val MAX_NAME_LENGTH: Int = 60
        const val MAX_PRESETS_PER_USER: Int = 12
    }
}

class SavedSwipeFilterException(
    val code: String,
) : RuntimeException(code)
