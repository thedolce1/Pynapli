package com.pynapli.server.appeals

import java.time.Instant
import java.util.UUID

interface AppealRepository {
    /**
     * Insert a new pending appeal. Returns null when a pending appeal already exists for the same
     * `(userId, subjectType, subjectRef)` (partial-unique-index conflict), letting the service map
     * that to `APPEAL_ALREADY_EXISTS` without a separate pre-check race.
     */
    suspend fun createAppeal(
        userId: UUID,
        subjectType: String,
        subjectRef: String?,
        reason: String,
    ): AppealRecord?

    suspend fun listAppealsForUser(userId: UUID): List<AppealRecord>

    /**
     * Keyset page of pending appeals ordered `created_at DESC, id DESC`. `cursor` is the last row of
     * the previous page (exclusive); null starts from the newest.
     */
    suspend fun listPendingAppeals(
        cursor: AppealCursor?,
        limit: Int,
    ): List<AppealRecord>

    suspend fun getAppeal(id: UUID): AppealRecord?

    /**
     * Transition a pending appeal to a terminal status. Returns null when the appeal does not exist
     * or is no longer pending (already resolved/withdrawn).
     */
    suspend fun resolveAppeal(
        id: UUID,
        status: String,
        adminId: String,
        note: String,
    ): AppealRecord?
}

data class AppealRecord(
    val id: UUID,
    val userId: UUID,
    val subjectType: String,
    val subjectRef: String?,
    val status: String,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val resolvedByAdminId: String?,
    val resolutionNote: String?,
)

data class AppealCursor(
    val createdAt: Instant,
    val id: UUID,
)
