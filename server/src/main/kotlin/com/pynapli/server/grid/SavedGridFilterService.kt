package com.pynapli.server.grid

import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SavedGridFilterDto
import com.pynapli.shared.network.dto.SavedGridFiltersListDto
import kotlinx.serialization.json.Json
import java.util.UUID

class SavedGridFilterService(
    private val repository: SavedGridFilterRepository,
    private val subscriptionService: SubscriptionService? = null,
    private val viewerTrustRepository: GridViewerTrustRepository? = null,
    private val json: Json = Json,
    private val idFactory: () -> UUID = UUID::randomUUID
) {

    suspend fun list(viewerUserId: UUID): SavedGridFiltersListDto {
        requireEntitlement(viewerUserId)
        return SavedGridFiltersListDto(
            items = repository.list(viewerUserId).map { it.toDto() }
        )
    }

    suspend fun create(
        viewerUserId: UUID,
        request: CreateSavedGridFilterRequestDto
    ): SavedGridFilterDto {
        requireEntitlement(viewerUserId)
        val name = request.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw SavedGridFilterException("INVALID_SAVED_FILTER_NAME")
        }
        val filters = validatePayload(request.payload)
        requireLifestyleFilterAccess(viewerUserId, filters)
        if (repository.countForUser(viewerUserId) >= MAX_PRESETS_PER_USER) {
            throw SavedGridFilterException("SAVED_FILTER_LIMIT_REACHED")
        }
        val payloadJson = json.encodeToString(GridQueryRequestDto.serializer(), request.payload.normalized())
        val record = try {
            repository.create(
                id = idFactory(),
                userId = viewerUserId,
                name = name,
                payloadJson = payloadJson
            )
        } catch (_: SavedGridFilterDuplicateNameException) {
            throw SavedGridFilterException("SAVED_FILTER_NAME_TAKEN")
        }
        return record.toDto()
    }

    suspend fun delete(viewerUserId: UUID, idText: String): Boolean {
        requireEntitlement(viewerUserId)
        val id = runCatching { UUID.fromString(idText) }
            .getOrElse { throw SavedGridFilterException("INVALID_SAVED_FILTER_ID") }
        return repository.delete(viewerUserId, id)
    }

    private suspend fun requireEntitlement(viewerUserId: UUID) {
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        if (!policy.advancedFiltersAllowed) {
            throw SavedGridFilterException("ENTITLEMENT_REQUIRED")
        }
    }

    private fun validatePayload(payload: GridQueryRequestDto): DiscoveryFilters {
        // Re-runs the same validation the live grid query uses; rejects unknown
        // discovery_styles / lifestyle / logistics tokens at save time.
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
            logisticsTags = payload.logisticsTags,
            tonightAvailableOnly = payload.tonightAvailableOnly,
            intents = payload.intents
        ) { _ -> SavedGridFilterException("INVALID_SAVED_FILTER_PAYLOAD") }
    }

    private suspend fun requireLifestyleFilterAccess(viewerUserId: UUID, filters: DiscoveryFilters) {
        if (filters.lifestyleTags.isEmpty()) return
        val verified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
        if (!verified) throw SavedGridFilterException("AGE_VERIFICATION_REQUIRED")
    }

    /**
     * Strips fields that should never be persisted in a preset (cursor is request-time
     * only; limit is server-controlled).
     */
    private fun GridQueryRequestDto.normalized(): GridQueryRequestDto =
        copy(cursor = null)

    private fun SavedGridFilterRecord.toDto(): SavedGridFilterDto = SavedGridFilterDto(
        id = id.toString(),
        name = name,
        payload = json.decodeFromString(GridQueryRequestDto.serializer(), payloadJson),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString()
    )

    companion object {
        const val MAX_NAME_LENGTH: Int = 60
        const val MAX_PRESETS_PER_USER: Int = 12
    }
}

class SavedGridFilterException(val code: String) : RuntimeException(code)
