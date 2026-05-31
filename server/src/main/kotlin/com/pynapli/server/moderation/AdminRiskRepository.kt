package com.pynapli.server.moderation

import java.time.Instant
import java.util.UUID

interface AdminRiskRepository {
    suspend fun listRiskStates(
        tierFilter: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminRiskStateRecord>

    suspend fun listRiskEvents(
        userId: UUID,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminRiskEventRecord>

    suspend fun setRiskTier(
        userId: UUID,
        tier: String,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord?

    suspend fun markRiskReviewed(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord?

    suspend fun lockAccount(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord?

    suspend fun banAccount(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord?
}

data class AdminRiskStateRecord(
    val userId: UUID,
    val displayName: String?,
    val score: Int,
    val tier: String,
    val lastEventAt: Instant?,
    val lastReviewedAt: Instant?,
    val updatedAt: Instant,
)

data class AdminRiskEventRecord(
    val eventId: UUID,
    val userId: UUID,
    val actorUserId: UUID?,
    val source: String,
    val reason: String,
    val delta: Int,
    val scoreAfter: Int,
    val tierAfter: String,
    val metadataJson: String,
    val createdAt: Instant,
)

data class AdminRiskActionRecord(
    val userId: UUID,
    val score: Int,
    val tier: String,
    val lastReviewedAt: Instant?,
    val updatedAt: Instant,
    val accountStatus: String? = null,
)
