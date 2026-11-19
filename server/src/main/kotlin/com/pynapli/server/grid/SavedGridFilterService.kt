package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.savedfilters.SavedFilter
import com.pynapli.server.savedfilters.SavedFilterDuplicateNameException
import com.pynapli.server.savedfilters.SavedFilterException
import com.pynapli.server.savedfilters.SavedFilterRecord
import com.pynapli.server.savedfilters.SavedFilterRepository
import com.pynapli.server.savedfilters.SavedFilterService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SavedGridFilterDto
import com.pynapli.shared.network.dto.SavedGridFiltersListDto
import kotlinx.serialization.json.Json
import java.util.UUID

typealias SavedGridFilterRepository = SavedFilterRepository
typealias SavedGridFilterRecord = SavedFilterRecord
typealias SavedGridFilterDuplicateNameException = SavedFilterDuplicateNameException
typealias SavedGridFilterException = SavedFilterException

/**
 * Grid surface of the shared [SavedFilterService]: maps the Grid request/response DTOs and
 * validates the payload with the same `discoveryFiltersFrom` call the live grid query uses.
 */
class SavedGridFilterService(
    repository: SavedGridFilterRepository,
    subscriptionService: SubscriptionService? = null,
    viewerTrustRepository: GridViewerTrustRepository? = null,
    json: Json = Json,
    idFactory: () -> UUID = UUID::randomUUID,
) {
    private val presets =
        SavedFilterService(
            repository = repository,
            payloadSerializer = GridQueryRequestDto.serializer(),
            validate = ::validatePayload,
            // Cursor is request-time only; limit is server-controlled.
            normalize = { payload -> payload.copy(cursor = null) },
            subscriptionService = subscriptionService,
            viewerTrustRepository = viewerTrustRepository,
            json = json,
            idFactory = idFactory,
        )

    suspend fun list(viewerUserId: UUID): SavedGridFiltersListDto = SavedGridFiltersListDto(items = presets.list(viewerUserId).map { it.toDto() })

    suspend fun create(
        viewerUserId: UUID,
        request: CreateSavedGridFilterRequestDto,
    ): SavedGridFilterDto = presets.create(viewerUserId, request.name, request.payload).toDto()

    suspend fun delete(
        viewerUserId: UUID,
        idText: String,
    ): Boolean = presets.delete(viewerUserId, idText)

    private fun SavedFilter<GridQueryRequestDto>.toDto(): SavedGridFilterDto =
        SavedGridFilterDto(
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

// Rejects unknown discovery_styles / lifestyle / logistics tokens at save time.
private fun validatePayload(payload: GridQueryRequestDto): DiscoveryFilters =
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
        boundaryItems = payload.boundaryItems,
        logisticsTags = payload.logisticsTags,
        tonightAvailableOnly = payload.tonightAvailableOnly,
        activeTodayOnly = payload.activeTodayOnly,
        recentlyJoinedOnly = payload.recentlyJoinedOnly,
        travelingHereOnly = payload.travelingHereOnly,
        withPhotosOnly = payload.withPhotosOnly,
        hasPrivateAlbumOnly = payload.hasPrivateAlbumOnly,
        intents = payload.intents,
        genders = payload.genders,
        orientations = payload.orientations,
        verifiedCouplesOnly = payload.verifiedCouplesOnly,
    ) { _ -> SavedFilterException("INVALID_SAVED_FILTER_PAYLOAD") }
