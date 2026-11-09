package com.pynapli.server.safety

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitServiceTest {
    @Test
    fun checkAllowsActionsWithinPolicyLimit() =
        runBlocking {
            val repository = SequencedRateLimitRepository(listOf(1, 2))
            val service = RateLimitService(repository)
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000701")

            val first = service.check(userId, RateLimitedAction.REPORT)
            val second = service.check(userId, RateLimitedAction.REPORT)

            assertTrue(first.allowed)
            assertTrue(second.allowed)
            assertEquals("pynapli:rate:report:$userId", repository.keys.single())
        }

    @Test
    fun checkDeniesWhenPolicyLimitIsExceededAndRecordsRiskSignal() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000711")
            val riskSignals = CapturingRateLimitRiskSignalPublisher()
            val service =
                RateLimitService(
                    repository = SequencedRateLimitRepository(listOf(31)),
                    riskSignalPublisher = riskSignals,
                )

            val decision = service.check(userId, RateLimitedAction.REPORT)

            assertFalse(decision.allowed)
            assertEquals(31, decision.count)
            assertEquals(30, decision.limit)
            assertEquals(1, riskSignals.captured.size)
            assertEquals(userId, riskSignals.captured.single().userId)
            assertEquals(RateLimitedAction.REPORT, riskSignals.captured.single().action)
        }

    @Test
    fun checkDeniesProfileViewVelocityWhenLimitIsExceeded() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000721")
            val repository = SequencedRateLimitRepository(listOf(241))
            val riskSignals = CapturingRateLimitRiskSignalPublisher()
            val service =
                RateLimitService(
                    repository = repository,
                    riskSignalPublisher = riskSignals,
                )

            val decision = service.check(userId, RateLimitedAction.PROFILE_VIEW)

            assertFalse(decision.allowed)
            assertEquals(241, decision.count)
            assertEquals(240, decision.limit)
            assertEquals(RateLimitedAction.PROFILE_VIEW, riskSignals.captured.single().action)
            assertEquals("pynapli:rate:profile_view:$userId", repository.keys.single())
        }

    @Test
    fun subjectRateLimitsHashTheSubjectBeforeWritingCounterKey() =
        runBlocking {
            val repository = SequencedRateLimitRepository(listOf(1))
            val service = RateLimitService(repository)

            val decision = service.checkSubject("Alex@Example.com", RateLimitedAction.AUTH_LOGIN)

            assertTrue(decision.allowed)
            assertEquals(RateLimitedAction.AUTH_LOGIN, decision.action)
            val key = repository.keys.single()
            assertTrue(key.startsWith("pynapli:rate:${RateLimitedAction.AUTH_LOGIN}:subject:"))
            assertFalse(key.contains("alex", ignoreCase = true))
            assertFalse(key.contains("example", ignoreCase = true))
        }
}

private class SequencedRateLimitRepository(
    private val counts: List<Long>,
) : RateLimitRepository {
    val keys = mutableSetOf<String>()
    private var index = 0

    override suspend fun increment(
        key: String,
        windowSeconds: Long,
    ): RateLimitCounter {
        keys += key
        val count = counts.getOrElse(index++) { counts.last() }
        return RateLimitCounter(count = count, ttlSeconds = windowSeconds)
    }
}

private class CapturingRateLimitRiskSignalPublisher : RateLimitRiskSignalPublisher {
    val captured = mutableListOf<CapturedRateLimitRiskSignal>()

    override suspend fun recordRateLimitHit(
        userId: UUID,
        action: String,
        count: Long,
        limit: Long,
        windowSeconds: Long,
    ) {
        captured += CapturedRateLimitRiskSignal(userId, action, count, limit, windowSeconds)
    }
}

private data class CapturedRateLimitRiskSignal(
    val userId: UUID,
    val action: String,
    val count: Long,
    val limit: Long,
    val windowSeconds: Long,
)
