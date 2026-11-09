package com.pynapli.server.safety

import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportTargetType
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportServiceTest {
    @Test
    fun submitReportPersistsValidReport() =
        runBlocking {
            val reporterId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val reportedId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository = InMemoryReportRepository()
            val riskPublisher = CapturingReportRiskSignalPublisher()
            val service = ReportService(repository, riskPublisher)

            val response =
                service.submitReport(
                    reporterUserId = reporterId,
                    request =
                        CreateReportRequestDto(
                            reportedUserId = reportedId.toString(),
                            targetType = ReportTargetType.PROFILE,
                            category = ReportCategory.HARASSMENT_OR_THREATS,
                            note = "Repeated harassment",
                        ),
                )

            assertEquals(reportedId.toString(), response.reportedUserId)
            assertEquals(ReportTargetType.PROFILE, response.targetType)
            assertEquals(ReportCategory.HARASSMENT_OR_THREATS, response.category)
            assertEquals("open", response.status)
            assertEquals(1, repository.captured.size)
            assertEquals("Repeated harassment", repository.captured.single().note)
            assertEquals(1, riskPublisher.captured.size)
            assertEquals(repository.captured.single().id, riskPublisher.captured.single().reportId)
            assertEquals(reportedId, riskPublisher.captured.single().reportedUserId)
            assertEquals(ReportCategory.HARASSMENT_OR_THREATS, riskPublisher.captured.single().category)
        }

    @Test
    fun submitReportRejectsSelfReport() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000010")
            val service = ReportService(InMemoryReportRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.submitReport(
                        viewerId,
                        CreateReportRequestDto(
                            reportedUserId = viewerId.toString(),
                            targetType = ReportTargetType.PROFILE,
                            category = ReportCategory.OTHER,
                        ),
                    )
                }

            assertEquals("REPORT_SELF_FORBIDDEN", error.code)
        }

    @Test
    fun submitReportRejectsUnknownCategory() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000020")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000021")
            val service = ReportService(InMemoryReportRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.submitReport(
                        viewerId,
                        CreateReportRequestDto(
                            reportedUserId = targetId.toString(),
                            targetType = ReportTargetType.PROFILE,
                            category = "made_up_category",
                        ),
                    )
                }

            assertEquals("INVALID_REPORT_CATEGORY", error.code)
        }

    @Test
    fun submitReportRejectsUnknownTargetType() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000030")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000031")
            val service = ReportService(InMemoryReportRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.submitReport(
                        viewerId,
                        CreateReportRequestDto(
                            reportedUserId = targetId.toString(),
                            targetType = "weird_surface",
                            category = ReportCategory.OTHER,
                        ),
                    )
                }

            assertEquals("INVALID_REPORT_TARGET", error.code)
        }

    @Test
    fun submitReportRejectsOversizedNote() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000040")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000041")
            val service = ReportService(InMemoryReportRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.submitReport(
                        viewerId,
                        CreateReportRequestDto(
                            reportedUserId = targetId.toString(),
                            targetType = ReportTargetType.PROFILE,
                            category = ReportCategory.OTHER,
                            note = "x".repeat(2_001),
                        ),
                    )
                }

            assertEquals("REPORT_NOTE_TOO_LONG", error.code)
        }

    @Test
    fun submitReportRejectsRateLimitedReporterBeforePersisting() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000050")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000051")
            val repository = InMemoryReportRepository()
            val service =
                ReportService(
                    repository = repository,
                    rateLimitService = denyingRateLimitService(),
                )

            val error =
                assertFailsWith<SafetyException> {
                    service.submitReport(
                        viewerId,
                        CreateReportRequestDto(
                            reportedUserId = targetId.toString(),
                            targetType = ReportTargetType.PROFILE,
                            category = ReportCategory.OTHER,
                        ),
                    )
                }

            assertEquals("RATE_LIMITED", error.code)
            assertEquals(emptyList(), repository.captured)
        }

    @Test
    fun listReportsReturnsOnlyUserSafeHistory() =
        runBlocking {
            val reporterId = UUID.fromString("00000000-0000-0000-0000-000000000060")
            val repository = InMemoryReportRepository()
            val service = ReportService(repository)
            repository.history +=
                ReportHistoryRecord(
                    id = UUID.fromString("00000000-0000-0000-0000-000000000061"),
                    reportedDisplayName = "Riley",
                    targetType = ReportTargetType.PROFILE,
                    category = ReportCategory.SPAM_OR_FAKE_PROFILE,
                    status = "dismissed",
                    createdAt = Instant.parse("2026-04-28T19:00:00Z"),
                )

            val response = service.listReports(reporterId)

            assertEquals(1, response.reports.size)
            assertEquals("Riley", response.reports.single().reportedDisplayName)
            assertEquals("reviewed", response.reports.single().status)
            assertEquals(ReportCategory.SPAM_OR_FAKE_PROFILE, response.reports.single().category)
        }

    @Test
    fun supportContactIncludesSafeAccountReference() {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000070")
        val service =
            ReportService(
                repository = InMemoryReportRepository(),
                supportEmail = "support@pynapli.com",
            )

        val contact = service.supportContact(viewerId)

        assertEquals("support@pynapli.com", contact.email)
        assertEquals("Pynapli support request", contact.subject)
        assertEquals(true, contact.body.contains(viewerId.toString()))
    }
}

private fun denyingRateLimitService(): RateLimitService =
    RateLimitService(
        object : RateLimitRepository {
            override suspend fun increment(
                key: String,
                windowSeconds: Long,
            ): RateLimitCounter = RateLimitCounter(count = Long.MAX_VALUE, ttlSeconds = windowSeconds)
        },
    )

private class CapturingReportRiskSignalPublisher : ReportRiskSignalPublisher {
    val captured = mutableListOf<CapturedReportRiskSignal>()

    override suspend fun recordReportCreated(
        reportId: UUID,
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        category: String,
    ) {
        captured +=
            CapturedReportRiskSignal(
                reportId = reportId,
                reporterUserId = reporterUserId,
                reportedUserId = reportedUserId,
                targetType = targetType,
                category = category,
            )
    }
}

private data class CapturedReportRiskSignal(
    val reportId: UUID,
    val reporterUserId: UUID,
    val reportedUserId: UUID,
    val targetType: String,
    val category: String,
)

private class InMemoryReportRepository : ReportRepository {
    val captured = mutableListOf<ReportRecord>()
    val history = mutableListOf<ReportHistoryRecord>()
    private val now = Instant.parse("2026-04-28T18:00:00Z")

    override suspend fun createReport(
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        targetId: String?,
        category: String,
        note: String?,
        reportIdFactory: () -> UUID,
    ): ReportRecord {
        val record =
            ReportRecord(
                id = reportIdFactory(),
                reporterUserId = reporterUserId,
                reportedUserId = reportedUserId,
                targetType = targetType,
                targetId = targetId,
                category = category,
                note = note,
                status = "open",
                createdAt = now,
            )
        captured.add(record)
        return record
    }

    override suspend fun createSystemReport(
        reportedUserId: UUID,
        targetType: String,
        targetId: String?,
        category: String,
        note: String?,
        reportIdFactory: () -> UUID,
    ): ReportRecord {
        val record =
            ReportRecord(
                id = reportIdFactory(),
                reporterUserId = reportedUserId,
                reportedUserId = reportedUserId,
                targetType = targetType,
                targetId = targetId,
                category = category,
                note = note,
                status = "open",
                createdAt = now,
                source = "system",
            )
        captured.add(record)
        return record
    }

    override suspend fun listReportsByReporter(
        reporterUserId: UUID,
        limit: Int,
    ): List<ReportHistoryRecord> = history.take(limit)
}
