package com.pynapli.server.safety

import java.time.Instant
import java.util.UUID

interface HiddenProfileRepository {
    suspend fun hideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
        hideIdFactory: () -> UUID = UUID::randomUUID,
    ): HiddenProfileRecord

    suspend fun unhideProfile(
        hiderUserId: UUID,
        hiddenUserId: UUID,
    ): Boolean

    suspend fun listHiddenProfiles(hiderUserId: UUID): List<HiddenProfileSummaryRecord>

    suspend fun listHiddenProfileIds(hiderUserId: UUID): Set<UUID>
}

data class HiddenProfileRecord(
    val id: UUID,
    val hiderUserId: UUID,
    val hiddenUserId: UUID,
    val createdAt: Instant,
)

data class HiddenProfileSummaryRecord(
    val hiddenUserId: UUID,
    val hiddenDisplayName: String?,
    val createdAt: Instant,
    // First approved profile photo, only while the account is active and visible and no block stands
    // either way (the unhide list avatar). Null otherwise.
    val photoMediaAssetId: UUID? = null,
)
