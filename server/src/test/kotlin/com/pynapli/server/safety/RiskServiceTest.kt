package com.pynapli.server.safety

import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportTargetType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskServiceTest {
    @Test
    fun recordReportCreatedStoresReportRiskSignal() =
        runBlocking {
            val repository = InMemoryRiskRepository()
            val service = RiskService(repository)
            val reportId = UUID.fromString("00000000-0000-0000-0000-000000000301")
            val reporterId = UUID.fromString("00000000-0000-0000-0000-000000000302")
            val reportedId = UUID.fromString("00000000-0000-0000-0000-000000000303")

            service.recordReportCreated(
                reportId = reportId,
                reporterUserId = reporterId,
                reportedUserId = reportedId,
                targetType = ReportTargetType.PROFILE,
                category = ReportCategory.UNDERAGE_USER,
            )

            val event = repository.events.single()
            assertEquals(reportedId, event.userId)
            assertEquals(reporterId, event.actorUserId)
            assertEquals(RiskService.SOURCE_REPORT, event.source)
            assertEquals("report.${ReportCategory.UNDERAGE_USER}", event.reason)
            assertEquals(35, event.delta)
            assertTrue(event.metadataJson.contains(reportId.toString()))
            assertEquals("watch", repository.states.getValue(reportedId).tier)
        }

    @Test
    fun repeatedSevereReportsCanReachQuarantineTier() =
        runBlocking {
            val repository = InMemoryRiskRepository()
            val service = RiskService(repository)
            val reporterId = UUID.fromString("00000000-0000-0000-0000-000000000312")
            val reportedId = UUID.fromString("00000000-0000-0000-0000-000000000313")

            repeat(3) { index ->
                service.recordReportCreated(
                    reportId = UUID.fromString("00000000-0000-0000-0000-00000000031${index + 4}"),
                    reporterUserId = reporterId,
                    reportedUserId = reportedId,
                    targetType = ReportTargetType.PROFILE,
                    category = ReportCategory.UNDERAGE_USER,
                )
            }

            val state = repository.states.getValue(reportedId)
            assertEquals(100, state.score)
            assertEquals("terminate", state.tier)
            assertEquals("terminate", repository.events.last().tierAfter)
        }

    @Test
    fun recordRateLimitHitStoresActionSignal() =
        runBlocking {
            val repository = InMemoryRiskRepository()
            val service = RiskService(repository)
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000321")

            service.recordRateLimitHit(
                userId = userId,
                action = RateLimitedAction.FIRST_MESSAGE,
                count = 31,
                limit = 30,
                windowSeconds = 3_600,
            )

            val event = repository.events.single()
            assertEquals(userId, event.userId)
            assertEquals(null, event.actorUserId)
            assertEquals(RiskService.SOURCE_RATE_LIMIT, event.source)
            assertEquals("rate_limit.${RateLimitedAction.FIRST_MESSAGE}", event.reason)
            assertEquals(6, event.delta)
            assertTrue(event.metadataJson.contains("first_message"))
        }

    @Test
    fun recordProfileViewRateLimitHitStoresVelocitySignal() =
        runBlocking {
            val repository = InMemoryRiskRepository()
            val service = RiskService(repository)
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000322")

            service.recordRateLimitHit(
                userId = userId,
                action = RateLimitedAction.PROFILE_VIEW,
                count = 241,
                limit = 240,
                windowSeconds = 3_600,
            )

            val event = repository.events.single()
            assertEquals(userId, event.userId)
            assertEquals(RiskService.SOURCE_RATE_LIMIT, event.source)
            assertEquals("rate_limit.${RateLimitedAction.PROFILE_VIEW}", event.reason)
            assertEquals(3, event.delta)
            assertTrue(event.metadataJson.contains("profile_view"))
        }

    @Test
    fun recordMessageHeldStoresReviewSignal() =
        runBlocking {
            val repository = InMemoryRiskRepository()
            val service = RiskService(repository)
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000331")
            val messageId = UUID.fromString("00000000-0000-0000-0000-000000000332")

            service.recordMessageHeld(
                userId = userId,
                messageId = messageId,
                reason = "link_first_message",
            )

            val event = repository.events.single()
            assertEquals(userId, event.userId)
            assertEquals(RiskService.SOURCE_MESSAGE_REVIEW, event.source)
            assertEquals("message_review.link_first_message", event.reason)
            assertEquals(8, event.delta)
            assertTrue(event.metadataJson.contains(messageId.toString()))
        }

    @Test
    fun recordImpossibleTravelStoresCoarseLocationSignal() =
        runBlocking {
            val repository = InMemoryRiskRepository()
            val service = RiskService(repository)
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000331")

            service.recordImpossibleTravel(
                userId = userId,
                distanceKm = 5234.7,
                elapsedSeconds = 600,
                speedKmh = 31408.2,
            )

            val event = repository.events.single()
            assertEquals(userId, event.userId)
            assertEquals(RiskService.SOURCE_LOCATION, event.source)
            assertEquals("location.impossible_travel", event.reason)
            assertEquals(RiskService.IMPOSSIBLE_TRAVEL_DELTA, event.delta)
            // Coarse facts only — never exact coordinates.
            assertTrue(event.metadataJson.contains("\"distanceKm\":\"5235\""))
            assertTrue(event.metadataJson.contains("\"elapsedSeconds\":\"600\""))
        }

    @Test
    fun ageVerificationPassedLowersScoreWithoutGoingNegative() =
        runBlocking {
            val repository = InMemoryRiskRepository()
            val service = RiskService(repository)
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000332")

            // Some prior risk, then verification.
            service.recordMessageHeld(userId, UUID.randomUUID(), "link_first_message")
            service.recordAgeVerificationPassed(userId)

            val event = repository.events.last()
            assertEquals(RiskService.SOURCE_VERIFICATION, event.source)
            assertEquals("verification.age_verified", event.reason)
            assertEquals(RiskService.AGE_VERIFIED_DELTA, event.delta)
            assertEquals(0, repository.states.getValue(userId).score)

            // Verification on a clean account stays clamped at zero.
            val cleanUserId = UUID.fromString("00000000-0000-0000-0000-000000000333")
            service.recordAgeVerificationPassed(cleanUserId)
            assertEquals(0, repository.states.getValue(cleanUserId).score)
        }
}

private class InMemoryRiskRepository : RiskRepository {
    val states = mutableMapOf<UUID, AccountRiskStateRecord>()
    val events = mutableListOf<RiskEventRecord>()
    private val now = Instant.parse("2026-05-01T00:00:00Z")

    override suspend fun recordEvent(
        userId: UUID,
        actorUserId: UUID?,
        source: String,
        reason: String,
        delta: Int,
        metadataJson: String,
        eventIdFactory: () -> UUID,
    ): RiskMutationRecord {
        val currentScore = states[userId]?.score ?: 0
        val nextScore = (currentScore + delta).coerceIn(0, 100)
        val state =
            AccountRiskStateRecord(
                userId = userId,
                score = nextScore,
                tier = nextScore.toRiskTier(),
                lastEventAt = now,
                lastReviewedAt = null,
                updatedAt = now,
            )
        states[userId] = state

        val event =
            RiskEventRecord(
                id = eventIdFactory(),
                userId = userId,
                actorUserId = actorUserId,
                source = source,
                reason = reason,
                delta = delta,
                scoreAfter = nextScore,
                tierAfter = state.tier,
                metadataJson = metadataJson,
                createdAt = now,
            )
        events += event
        return RiskMutationRecord(event = event, state = state)
    }

    override suspend fun getState(userId: UUID): AccountRiskStateRecord? = states[userId]

    private fun Int.toRiskTier(): String =
        when {
            this >= 90 -> "terminate"
            this >= 70 -> "quarantine"
            this >= 50 -> "friction"
            this >= 30 -> "watch"
            else -> "clean"
        }
}
