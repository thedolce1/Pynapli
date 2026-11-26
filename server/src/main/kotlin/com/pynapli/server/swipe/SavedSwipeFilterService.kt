package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.grid.GridViewerTrustRepository
import com.pynapli.server.savedfilters.SavedFilter
import com.pynapli.server.savedfilters.SavedFilterDuplicateNameException
import com.pynapli.server.savedfilters.SavedFilterException
import com.pynapli.server.savedfilters.SavedFilterRecord
import com.pynapli.server.savedfilters.SavedFilterRepository
import com.pynapli.server.savedfilters.SavedFilterService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SavedSwipeFiltersListDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.serialization.json.Json
import java.util.UUID

typealias SavedSwipeFilterRepository = SavedFilterRepository
typealias SavedSwipeFilterRecord = SavedFilterRecord
typealias SavedSwipeFilterDuplicateNameException = SavedFilterDuplicateNameException
typealias SavedSwipeFilterException = SavedFilterException

/**
 * Swipe surface of the shared [SavedFilterService]. Payload is the verbatim `SwipeDeckRequestDto`
 * minus cursor; validation runs the same `discoveryFiltersFrom` and `SwipeDealbreakerKey.ALL`
 * checks the live `SwipeService.queryDeck` uses, so a taxonomy or dealbreaker change rejects
 * stale presets on save.
 */
class SavedSwipeFilterService(
    repository: SavedSwipeFilterRepository,
    subscriptionService: SubscriptionService? = null,
    viewerTrustRepository: GridViewerTrustRepository? = null,
    json: Json = Json,
    idFactory: () -> UUID = UUID::randomUUID,
) {
    private val presets =
        SavedFilterService(
            repository = repository,
            payloadSerializer = SwipeDeckRequestDto.serializer(),
            validate = ::validatePayload,
            // Cursor is request-time only; limit is the server default for the preset's fresh deck.
            normalize = { payload -> payload.copy(cursor = null) },
            subscriptionService = subscriptionService,
            viewerTrustRepository = viewerTrustRepository,
            json = json,
            idFactory = idFactory,
        )

    suspend fun list(viewerUserId: UUID): SavedSwipeFiltersListDto = SavedSwipeFiltersListDto(items = presets.list(viewerUserId).map { it.toDto() })

    suspend fun create(
        viewerUserId: UUID,
        request: CreateSavedSwipeFilterRequestDto,
    ): SavedSwipeFilterDto = presets.create(viewerUserId, request.name, request.payload).toDto()

    suspend fun delete(
        viewerUserId: UUID,
        idText: String,
    ): Boolean = presets.delete(viewerUserId, idText)

    private fun SavedFilter<SwipeDeckRequestDto>.toDto(): SavedSwipeFilterDto =
        SavedSwipeFilterDto(
            id = id.toString(),
            name = name,
            payload = payload,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    companion object {
        const val MAX_NAME_LENGTH: Int = SavedFilterService.MAX_NAME_LENGTH
        const val MAX_PRESETS_PER_USER: Int = SavedFilterService.MAX_PRESETS_PER_USER
    }
}

private fun validatePayload(payload: SwipeDeckRequestDto): DiscoveryFilters {
    payload.dealbreakers.forEach { key ->
        if (key.trim() !in SwipeDealbreakerKey.ALL) {
            throw SavedFilterException("INVALID_SAVED_FILTER_PAYLOAD")
        }
    }
    return discoveryFiltersFrom(
        minimumAge = payload.minimumAge,
        maximumAge = payload.maximumAge,
        relationshipContexts = payload.relationshipContexts,
        linkedOnly = payload.linkedOnly,
        verifiedOnly = payload.verifiedOnly,
        facePhotoVerifiedOnly = payload.facePhotoVerifiedOnly,
        ageVerifiedOnly = payload.ageVerifiedOnly,
        discoveryStyles = payload.discoveryStyles,
        lifestyleTags = payload.lifestyleTags,
        boundaryItems = payload.boundaryItems,
        logisticsTags = payload.logisticsTags,
        withPhotosOnly = payload.withPhotosOnly,
        hasPrivateAlbumOnly = payload.hasPrivateAlbumOnly,
        intents = payload.intents,
        genders = payload.genders,
        orientations = payload.orientations,
        verifiedPreferred = payload.verifiedPreferred,
        verifiedCouplesOnly = payload.verifiedCouplesOnly,
        partnerGenders = payload.partnerGenders,
        partnerMinimumAge = payload.partnerMinimumAge,
        partnerMaximumAge = payload.partnerMaximumAge,
        partnerOrientations = payload.partnerOrientations,
        partnerVerifiedOnly = payload.partnerVerifiedOnly,
        excludeSingleMen = payload.excludeSingleMen,
    ) { _ -> SavedFilterException("INVALID_SAVED_FILTER_PAYLOAD") }
}
