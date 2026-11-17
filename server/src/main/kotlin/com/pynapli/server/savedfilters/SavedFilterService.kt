package com.pynapli.server.savedfilters

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.grid.GridViewerTrustRepository
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Saved filter presets (FILTERS_SPEC L463-477) for both discovery surfaces. One set of rules:
 * entitlement gate (`advancedFiltersAllowed`), name rules, payload re-validation through the same
 * checks the live query uses, age-verified gate for lifestyle tags, per-user cap, duplicate names.
 *
 * [P] is the surface's stored request payload (`GridQueryRequestDto` / `SwipeDeckRequestDto`).
 * The surface supplies [validate] (returns the parsed filters or throws [SavedFilterException]
 * `INVALID_SAVED_FILTER_PAYLOAD`) and [normalize] (strips request-time fields such as the cursor).
 */
class SavedFilterService<P>(
    private val repository: SavedFilterRepository,
    private val payloadSerializer: KSerializer<P>,
    private val validate: (P) -> DiscoveryFilters,
    private val normalize: (P) -> P,
    private val subscriptionService: SubscriptionService? = null,
    private val viewerTrustRepository: GridViewerTrustRepository? = null,
    private val json: Json = Json,
    private val idFactory: () -> UUID = UUID::randomUUID,
) {
    suspend fun list(viewerUserId: UUID): List<SavedFilter<P>> {
        requireEntitlement(viewerUserId)
        return repository.list(viewerUserId).map { it.decode() }
    }

    suspend fun create(
        viewerUserId: UUID,
        rawName: String,
        payload: P,
    ): SavedFilter<P> {
        requireEntitlement(viewerUserId)
        val name = rawName.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw SavedFilterException("INVALID_SAVED_FILTER_NAME")
        }
        val filters = validate(payload)
        requireLifestyleFilterAccess(viewerUserId, filters)
        if (repository.countForUser(viewerUserId) >= MAX_PRESETS_PER_USER) {
            throw SavedFilterException("SAVED_FILTER_LIMIT_REACHED")
        }
        val payloadJson = json.encodeToString(payloadSerializer, normalize(payload))
        val record =
            try {
                repository.create(
                    id = idFactory(),
                    userId = viewerUserId,
                    name = name,
                    payloadJson = payloadJson,
                )
            } catch (_: SavedFilterDuplicateNameException) {
                throw SavedFilterException("SAVED_FILTER_NAME_TAKEN")
            }
        return record.decode()
    }

    suspend fun delete(
        viewerUserId: UUID,
        idText: String,
    ): Boolean {
        requireEntitlement(viewerUserId)
        val id =
            runCatching { UUID.fromString(idText) }
                .getOrElse { throw SavedFilterException("INVALID_SAVED_FILTER_ID") }
        return repository.delete(viewerUserId, id)
    }

    private suspend fun requireEntitlement(viewerUserId: UUID) {
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        if (!policy.advancedFiltersAllowed) {
            throw SavedFilterException("ENTITLEMENT_REQUIRED")
        }
    }

    private suspend fun requireLifestyleFilterAccess(
        viewerUserId: UUID,
        filters: DiscoveryFilters,
    ) {
        if (filters.lifestyleTags.isEmpty()) return
        val verified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
        if (!verified) throw SavedFilterException("AGE_VERIFICATION_REQUIRED")
    }

    private fun SavedFilterRecord.decode(): SavedFilter<P> =
        SavedFilter(
            id = id,
            name = name,
            payload = json.decodeFromString(payloadSerializer, payloadJson),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    companion object {
        const val MAX_NAME_LENGTH: Int = 60
        const val MAX_PRESETS_PER_USER: Int = 12
    }
}

data class SavedFilter<P>(
    val id: UUID,
    val name: String,
    val payload: P,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class SavedFilterException(
    val code: String,
) : RuntimeException(code)
