package com.pynapli.server.moderation

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminRiskServiceTest {
    @Test
    fun listRiskStatesRejectsInvalidTierFilter() = runBlocking {
        val service = AdminRiskService(RecordingAdminRiskRepository())

        val error = assertFailsWith<ModerationException> {
            service.listRiskStates(
                tierFilterText = "unknown",
                limitText = null,
                actorId = "moderator-1",
                reason = "risk review"
            )
        }

        assertEquals("INVALID_RISK_TIER_FILTER", error.code)
    }

    @Test
    fun setRiskTierRejectsInvalidTier() = runBlocking {
        val service = AdminRiskService(RecordingAdminRiskRepository())

        val error = assertFailsWith<ModerationException> {
            service.setRiskTier(
                userIdText = UUID.randomUUID().toString(),
                tierText = "shadow",
                actorId = "moderator-1",
                reason = "risk review"
            )
        }

        assertEquals("INVALID_RISK_TIER", error.code)
    }

    @Test
    fun clearRiskRequiresReason() = runBlocking {
        val service = AdminRiskService(RecordingAdminRiskRepository())

        val error = assertFailsWith<ModerationException> {
            service.clearRisk(
                userIdText = UUID.randomUUID().toString(),
                actorId = "moderator-1",
                reason = " "
            )
        }

        assertEquals("MODERATION_REASON_REQUIRED", error.code)
    }

    @Test
    fun markRiskReviewedReturnsNotFoundWhenRepositoryMisses() = runBlocking {
        val service = AdminRiskService(RecordingAdminRiskRepository())

        val error = assertFailsWith<ModerationException> {
            service.markRiskReviewed(
                userIdText = UUID.randomUUID().toString(),
                actorId = "moderator-1",
                reason = "reviewed"
            )
        }

        assertEquals("RISK_REVIEW_NOT_FOUND", error.code)
    }

    @Test
    fun clearRiskSetsCleanTier() = runBlocking {
        val userId = UUID.randomUUID()
        val repository = RecordingAdminRiskRepository(
            action = AdminRiskActionRecord(
                userId = userId,
                score = 0,
                tier = "clean",
                lastReviewedAt = Instant.parse("2026-04-24T18:00:00Z"),
                updatedAt = Instant.parse("2026-04-24T18:00:00Z")
            )
        )
        val service = AdminRiskService(repository)

        val action = service.clearRisk(
            userIdText = userId.toString(),
            actorId = " moderator-1 ",
            reason = " false positive "
        )

        assertEquals("clean", action.tier)
        assertEquals("clean", repository.lastTier)
        assertEquals("moderator-1", repository.lastActorId)
        assertEquals("false positive", repository.lastReason)
    }

    @Test
    fun lockAccountDelegatesWithNormalizedAdminContext() = runBlocking {
        val userId = UUID.randomUUID()
        val repository = RecordingAdminRiskRepository(
            action = AdminRiskActionRecord(
                userId = userId,
                score = 90,
                tier = "terminate",
                lastReviewedAt = Instant.parse("2026-04-24T18:00:00Z"),
                updatedAt = Instant.parse("2026-04-24T18:00:00Z"),
                accountStatus = "suspended"
            )
        )
        val service = AdminRiskService(repository)

        val action = service.lockAccount(
            userIdText = userId.toString(),
            actorId = " moderator-1 ",
            reason = " severe spam "
        )

        assertEquals("terminate", action.tier)
        assertEquals("suspended", action.accountStatus)
        assertEquals("lock", repository.lastEnforcementAction)
        assertEquals("moderator-1", repository.lastActorId)
        assertEquals("severe spam", repository.lastReason)
    }

    @Test
    fun banAccountReturnsNotFoundWhenRepositoryMisses() = runBlocking {
        val service = AdminRiskService(RecordingAdminRiskRepository())

        val error = assertFailsWith<ModerationException> {
            service.banAccount(
                userIdText = UUID.randomUUID().toString(),
                actorId = "moderator-1",
                reason = "confirmed severe abuse"
            )
        }

        assertEquals("RISK_REVIEW_NOT_FOUND", error.code)
    }
}

private class RecordingAdminRiskRepository(
    private val action: AdminRiskActionRecord? = null
) : AdminRiskRepository {
    var lastTier: String? = null
    var lastActorId: String? = null
    var lastReason: String? = null
    var lastEnforcementAction: String? = null

    override suspend fun listRiskStates(
        tierFilter: String,
        limit: Int,
        actorId: String,
        reason: String
    ): List<AdminRiskStateRecord> {
        lastTier = tierFilter
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun listRiskEvents(
        userId: UUID,
        limit: Int,
        actorId: String,
        reason: String
    ): List<AdminRiskEventRecord> {
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun setRiskTier(
        userId: UUID,
        tier: String,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? {
        lastTier = tier
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun markRiskReviewed(
        userId: UUID,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun lockAccount(
        userId: UUID,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? {
        lastEnforcementAction = "lock"
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun banAccount(
        userId: UUID,
        actorId: String,
        reason: String
    ): AdminRiskActionRecord? {
        lastEnforcementAction = "ban"
        lastActorId = actorId
        lastReason = reason
        return action
    }
}
