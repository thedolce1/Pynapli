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

    /**
     * Records a user report's risk event under [ReportScoringPolicy] (TRUST_AND_SAFETY_SPEC, AUDIT-15):
     * the delta actually applied may be smaller than [requestedDelta], or zero. The event is always
     * written, with the requested and counted deltas in its metadata, so moderators see every report.
     */
    suspend fun recordReportEvent(
        userId: UUID,
        reporterUserId: UUID,
        reason: String,
        requestedDelta: Int,
        metadataJson: String = "{}",
        policy: ReportScoringPolicy = ReportScoringPolicy(),
        eventIdFactory: () -> UUID = UUID::randomUUID,
    ): RiskMutationRecord

    suspend fun getState(userId: UUID): AccountRiskStateRecord?
}

/**
 * How much a user report may move the reported account's risk score. Reports are easy to file and
 * easy to coordinate, so on their own they never take an account past what a human should decide:
 * - a reporter counts once per reason per [window] (repeats are kept, at zero weight);
 * - with fewer than [quarantineMinDistinctReporters] distinct reporters in the window, a report can
 *   lift the score to [singleReporterCeiling] at most (just under quarantine);
 * - with more, to [reportCeiling] at most (just under terminate). Terminate needs a moderator.
 * Other risk signals (media scans, rate limits, location, devices) are unaffected.
 */
data class ReportScoringPolicy(
    val window: java.time.Duration = java.time.Duration.ofDays(30),
    val quarantineMinDistinctReporters: Int = 2,
    val singleReporterCeiling: Int = 69,
    val reportCeiling: Int = 89,
) {
    /** The delta to apply for a report given the current score and what the window already holds. */
    fun countedDelta(
        currentScore: Int,
        requestedDelta: Int,
        duplicate: Boolean,
        distinctReporters: Int,
    ): Int {
        if (duplicate || requestedDelta <= 0) return 0
        val ceiling = if (distinctReporters >= quarantineMinDistinctReporters) reportCeiling else singleReporterCeiling
        return (minOf(currentScore + requestedDelta, ceiling) - currentScore).coerceAtLeast(0)
    }
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
