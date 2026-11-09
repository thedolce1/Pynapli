package com.pynapli.server.moderation

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminReportServiceTest {
    @Test
    fun listReportsRejectsInvalidStatus() =
        runBlocking {
            val service = AdminReportService(RecordingAdminReportRepository())

            val error =
                assertFailsWith<ModerationException> {
                    service.listReports(
                        statusText = "deleted",
                        limitText = null,
                        actorId = "moderator-1",
                        reason = "queue review",
                    )
                }

            assertEquals("INVALID_REPORT_REVIEW_STATUS", error.code)
        }

    @Test
    fun resolveReportRequiresReason() =
        runBlocking {
            val service = AdminReportService(RecordingAdminReportRepository())

            val error =
                assertFailsWith<ModerationException> {
                    service.resolveReport(
                        reportIdText = UUID.randomUUID().toString(),
                        actorId = "moderator-1",
                        reason = " ",
                    )
                }

            assertEquals("MODERATION_REASON_REQUIRED", error.code)
        }

    @Test
    fun dismissReportReturnsNotFoundWhenRepositoryMisses() =
        runBlocking {
            val service = AdminReportService(RecordingAdminReportRepository())

            val error =
                assertFailsWith<ModerationException> {
                    service.dismissReport(
                        reportIdText = UUID.randomUUID().toString(),
                        actorId = "moderator-1",
                        reason = "not actionable",
                    )
                }

            assertEquals("REPORT_REVIEW_NOT_FOUND", error.code)
        }

    @Test
    fun markUnderReviewPassesNormalizedInputs() =
        runBlocking {
            val reportId = UUID.randomUUID()
            val repository =
                RecordingAdminReportRepository(
                    action =
                        AdminReportActionRecord(
                            reportId = reportId,
                            status = "under_review",
                            actedAt = Instant.parse("2026-04-24T18:00:00Z"),
                        ),
                )
            val service = AdminReportService(repository)

            val action =
                service.markUnderReview(
                    reportIdText = reportId.toString(),
                    actorId = " moderator-1 ",
                    reason = " queue triage ",
                )

            assertEquals("under_review", action.status)
            assertEquals("moderator-1", repository.lastActorId)
            assertEquals("queue triage", repository.lastReason)
        }

    @Test
    fun resolveRemoveMediaPassesNormalizedInputs() =
        runBlocking {
            val reportId = UUID.randomUUID()
            val repository =
                RecordingAdminReportRepository(
                    action =
                        AdminReportActionRecord(
                            reportId = reportId,
                            status = "resolved",
                            actedAt = Instant.parse("2026-04-24T18:00:00Z"),
                        ),
                )
            val service = AdminReportService(repository)

            val action =
                service.resolveReportAndRemoveMedia(
                    reportIdText = reportId.toString(),
                    actorId = " moderator-2 ",
                    reason = " remove reported media ",
                )

            assertEquals("resolved", action.status)
            assertEquals("moderator-2", repository.lastActorId)
            assertEquals("remove reported media", repository.lastReason)
            assertEquals(true, repository.resolveRemoveMediaCalled)
        }
}

private class RecordingAdminReportRepository(
    private val action: AdminReportActionRecord? = null,
) : AdminReportRepository {
    var lastActorId: String? = null
    var lastReason: String? = null
    var resolveRemoveMediaCalled: Boolean = false

    override suspend fun listReports(
        status: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminReportRecord> {
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun updateReportStatus(
        reportId: UUID,
        status: String,
        actorId: String,
        reason: String,
    ): AdminReportActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun resolveReportAndRemoveMedia(
        reportId: UUID,
        actorId: String,
        reason: String,
    ): AdminReportActionRecord? {
        resolveRemoveMediaCalled = true
        lastActorId = actorId
        lastReason = reason
        return action
    }
}
