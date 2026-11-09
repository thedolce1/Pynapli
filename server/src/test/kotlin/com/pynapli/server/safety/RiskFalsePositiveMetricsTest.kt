package com.pynapli.server.safety

import com.pynapli.server.moderation.AdminAppealActionRecord
import com.pynapli.server.moderation.AdminAppealRecord
import com.pynapli.server.moderation.AdminAppealRepository
import com.pynapli.server.moderation.AdminAppealService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RiskFalsePositiveMetricsTest {
    @Test
    fun countsDecisionsByOutcomeAndDerivesOverturnTotals() {
        val metrics = RiskFalsePositiveMetrics()

        metrics.recordAppealDecision("upheld")
        metrics.recordAppealDecision("upheld")
        metrics.recordAppealDecision("restored")
        metrics.recordAppealDecision("reduced")
        metrics.recordAppealDecision("escalated")

        assertEquals(
            mapOf("escalated" to 1L, "reduced" to 1L, "restored" to 1L, "upheld" to 2L),
            metrics.snapshot(),
        )
        assertEquals(2, metrics.overturnedTotal())
        assertEquals(5, metrics.decidedTotal())
    }

    @Test
    fun unsafeLabelsCoerceToUnknown() {
        val metrics = RiskFalsePositiveMetrics()

        metrics.recordAppealDecision("DROP TABLE appeals; --")
        metrics.recordAppealDecision("")

        assertEquals(mapOf("unknown" to 2L), metrics.snapshot())
        assertEquals(0, metrics.overturnedTotal())
    }

    @Test
    fun adminAppealDecisionsFeedTheMetrics() =
        runBlocking {
            val metrics = RiskFalsePositiveMetrics()
            val service =
                AdminAppealService(
                    repository = StubAdminAppealRepository(),
                    falsePositiveMetrics = metrics,
                )

            service.decideAppeal(
                appealIdText = UUID.randomUUID().toString(),
                outcomeText = "restored",
                actorId = "admin-1",
                reason = "wrongly suspended",
                adminNote = null,
            )
            service.decideAppeal(
                appealIdText = UUID.randomUUID().toString(),
                outcomeText = "upheld",
                actorId = "admin-1",
                reason = "valid enforcement",
                adminNote = null,
            )

            assertEquals(1, metrics.overturnedTotal())
            assertEquals(2, metrics.decidedTotal())
        }
}

private class StubAdminAppealRepository : AdminAppealRepository {
    override suspend fun listAppeals(
        status: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminAppealRecord> = emptyList()

    override suspend fun markUnderReview(
        appealId: UUID,
        actorId: String,
        reason: String,
    ): AdminAppealActionRecord? = null

    override suspend fun decideAppeal(
        appealId: UUID,
        status: String,
        actorId: String,
        reason: String,
        adminNote: String?,
    ): AdminAppealActionRecord =
        AdminAppealActionRecord(
            appealId = appealId,
            userId = UUID.randomUUID(),
            status = status,
            actedAt = Instant.parse("2026-06-10T00:00:00Z"),
        )
}
