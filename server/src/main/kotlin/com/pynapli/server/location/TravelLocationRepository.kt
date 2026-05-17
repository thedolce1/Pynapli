package com.pynapli.server.location

import java.time.Instant
import java.util.UUID

interface TravelLocationRepository {
    /** Returns the user's currently active travel location, or null if none. */
    suspend fun findActive(userId: UUID): TravelLocationRecord?

    /**
     * Sets a new active travel location. Any prior active row for this user is
     * moved to status='cleared' with active_until = [now] in the same TX, so
     * the partial unique index `uniq_travel_locations_user_active` is honored.
     */
    suspend fun setActive(
        userId: UUID,
        request: TravelLocationUpsert,
        now: Instant
    ): TravelLocationRecord

    /**
     * Marks the user's currently active travel row as cleared. Returns true if
     * a row was cleared, false if there was nothing active.
     */
    suspend fun clearActive(userId: UUID, now: Instant): Boolean

    /**
     * Count of travel_locations rows the user has created on or after [since].
     * Used by the Social-tier preview rate limit (MONETIZATION_SPEC L186):
     * Social viewers are limited to N activations per 30 days; Select viewers
     * skip this check.
     */
    suspend fun countActivationsSince(userId: UUID, since: Instant): Int
}

data class TravelLocationUpsert(
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val region: String?,
    val country: String?
)

data class TravelLocationRecord(
    val id: UUID,
    val userId: UUID,
    val city: String,
    val region: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val activeFrom: Instant,
    val activeUntil: Instant?,
    val status: String
)
