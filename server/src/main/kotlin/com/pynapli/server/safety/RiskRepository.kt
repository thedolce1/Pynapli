package com.pynapli.server.safety

import java.time.Instant
import java.util.UUID

interface RiskRepository {
    suspend fun recordEvent(
        userId: UUID,
        actorUserId: UUID?,
        source: String,
        reason: String,
        delta: Int,
        metadataJson: String = "{}",
        eventIdFactory: () -> UUID = UUID::randomUUID,
    ): RiskMutationRecord

    suspend fun getState(userId: UUID): AccountRiskStateRecord?
}

data class RiskMutationRecord(
    val event: RiskEventRecord,
    val state: AccountRiskStateRecord,
)

data class AccountRiskStateRecord(
    val userId: UUID,
    val score: Int,
    val tier: String,
    val lastEventAt: Instant?,
    val lastReviewedAt: Instant?,
    val updatedAt: Instant,
)

data class RiskEventRecord(
    val id: UUID,
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
