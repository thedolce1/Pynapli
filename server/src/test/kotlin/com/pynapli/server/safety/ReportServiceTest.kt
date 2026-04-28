package com.pynapli.server.safety

import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportTargetType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportServiceTest {
    @Test
    fun submitReportPersistsValidReport() = runBlocking {
        val reporterId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val reportedId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val repository = InMemoryReportRepository()
        val service = ReportService(repository)

        val response = service.submitReport(
            reporterUserId = reporterId,
            request = CreateReportRequestDto(
                reportedUserId = reportedId.toString(),
                targetType = ReportTargetType.PROFILE,
                category = ReportCategory.HARASSMENT_OR_THREATS,
                note = "Repeated harassment"
            )
        )

        assertEquals(reportedId.toString(), response.reportedUserId)
        assertEquals(ReportTargetType.PROFILE, response.targetType)
        assertEquals(ReportCategory.HARASSMENT_OR_THREATS, response.category)
        assertEquals("open", response.status)
        assertEquals(1, repository.captured.size)
        assertEquals("Repeated harassment", repository.captured.single().note)
    }

    @Test
    fun submitReportRejectsSelfReport() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val service = ReportService(InMemoryReportRepository())

        val error = assertFailsWith<SafetyException> {
            service.submitReport(
                viewerId,
                CreateReportRequestDto(
                    reportedUserId = viewerId.toString(),
                    targetType = ReportTargetType.PROFILE,
                    category = ReportCategory.OTHER
                )
            )
        }

        assertEquals("REPORT_SELF_FORBIDDEN", error.code)
    }

    @Test
    fun submitReportRejectsUnknownCategory() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000021")
        val service = ReportService(InMemoryReportRepository())

        val error = assertFailsWith<SafetyException> {
            service.submitReport(
                viewerId,
                CreateReportRequestDto(
                    reportedUserId = targetId.toString(),
                    targetType = ReportTargetType.PROFILE,
                    category = "made_up_category"
                )
            )
        }

        assertEquals("INVALID_REPORT_CATEGORY", error.code)
    }

    @Test
    fun submitReportRejectsUnknownTargetType() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000030")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000031")
        val service = ReportService(InMemoryReportRepository())

        val error = assertFailsWith<SafetyException> {
            service.submitReport(
                viewerId,
                CreateReportRequestDto(
                    reportedUserId = targetId.toString(),
                    targetType = "weird_surface",
                    category = ReportCategory.OTHER
                )
            )
        }

        assertEquals("INVALID_REPORT_TARGET", error.code)
    }

    @Test
    fun submitReportRejectsOversizedNote() = runBlocking {
        val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000040")
        val targetId = UUID.fromString("00000000-0000-0000-0000-000000000041")
        val service = ReportService(InMemoryReportRepository())

        val error = assertFailsWith<SafetyException> {
            service.submitReport(
                viewerId,
                CreateReportRequestDto(
                    reportedUserId = targetId.toString(),
                    targetType = ReportTargetType.PROFILE,
                    category = ReportCategory.OTHER,
                    note = "x".repeat(2_001)
                )
            )
        }

        assertEquals("REPORT_NOTE_TOO_LONG", error.code)
    }
}

private class InMemoryReportRepository : ReportRepository {
    val captured = mutableListOf<ReportRecord>()
    private val now = Instant.parse("2026-04-28T18:00:00Z")

    override suspend fun createReport(
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        targetId: String?,
        category: String,
        note: String?,
        reportIdFactory: () -> UUID
    ): ReportRecord {
        val record = ReportRecord(
            id = reportIdFactory(),
            reporterUserId = reporterUserId,
            reportedUserId = reportedUserId,
            targetType = targetType,
            targetId = targetId,
            category = category,
            note = note,
            status = "open",
            createdAt = now
        )
        captured.add(record)
        return record
    }
}
