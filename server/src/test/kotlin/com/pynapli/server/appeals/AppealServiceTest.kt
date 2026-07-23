package com.pynapli.server.appeals

import com.pynapli.server.moderation.AdminRiskActionRecord
import com.pynapli.server.moderation.AdminRiskService
import com.pynapli.shared.network.dto.AdminResolveAppealRequestDto
import com.pynapli.shared.network.dto.SubmitAppealRequestDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppealServiceTest {
    private val now = Instant.parse("2026-06-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val appealId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    @Test
    fun submitAccountAppealAllowedWhenSuspendedWithinWindow() =
        runBlocking {
            val repository = mockk<AppealRepository>()
            val eligibility = mockk<AppealEligibilityRepository>()
            coEvery { eligibility.riskStateForUser(userId) } returns
                AppealRiskState(tier = "quarantine", actionedAt = now.minus(Duration.ofDays(5)))
            coEvery { repository.createAppeal(userId, "account", null, any()) } returns
                appealRecord(status = "pending")
            val service = service(repository, eligibility)

            val result =
                service.submitAppeal(
                    userId,
                    SubmitAppealRequestDto(subjectType = "account", subjectRef = null, reason = "I was wrongly suspended."),
                )

            assertEquals("pending", result.status)
            assertEquals("account", result.subjectType)
            coVerify(exactly = 1) { repository.createAppeal(userId, "account", null, any()) }
        }

    @Test
    fun submitAccountAppealRejectedWhenNoActionableState() =
        runBlocking {
            val repository = mockk<AppealRepository>()
            val eligibility = mockk<AppealEligibilityRepository>()
            // tier 'clean' => nothing to appeal.
            coEvery { eligibility.riskStateForUser(userId) } returns
                AppealRiskState(tier = "clean", actionedAt = now.minus(Duration.ofDays(1)))
            val service = service(repository, eligibility)

            val error =
                assertFailsWith<AppealException> {
                    service.submitAppeal(userId, SubmitAppealRequestDto("account", null, "Let me back in."))
                }

            assertEquals("APPEAL_NOT_ALLOWED", error.code)
            coVerify(exactly = 0) { repository.createAppeal(any(), any(), any(), any()) }
        }

    @Test
    fun submitAccountAppealRejectedWhenWindowExpired() =
        runBlocking {
            val repository = mockk<AppealRepository>()
            val eligibility = mockk<AppealEligibilityRepository>()
            coEvery { eligibility.riskStateForUser(userId) } returns
                AppealRiskState(tier = "terminate", actionedAt = now.minus(Duration.ofDays(31)))
            val service = service(repository, eligibility)

            val error =
                assertFailsWith<AppealException> {
                    service.submitAppeal(userId, SubmitAppealRequestDto("account", null, "Please review."))
                }

            assertEquals("APPEAL_WINDOW_EXPIRED", error.code)
        }

    @Test
    fun submitAccountAppealRejectedWhenDuplicatePending() =
        runBlocking {
            val repository = mockk<AppealRepository>()
            val eligibility = mockk<AppealEligibilityRepository>()
            coEvery { eligibility.riskStateForUser(userId) } returns
                AppealRiskState(tier = "quarantine", actionedAt = now.minus(Duration.ofDays(2)))
            // Repository returns null on partial-unique-index conflict.
            coEvery { repository.createAppeal(userId, "account", null, any()) } returns null
            val service = service(repository, eligibility)

            val error =
                assertFailsWith<AppealException> {
                    service.submitAppeal(userId, SubmitAppealRequestDto("account", null, "Second try."))
                }

            assertEquals("APPEAL_ALREADY_EXISTS", error.code)
        }

    @Test
    fun submitAppealRejectedWhenNotAllowedByPolicy() =
        runBlocking {
            val repository = mockk<AppealRepository>()
            val eligibility = mockk<AppealEligibilityRepository>()
            val service =
                AppealService(
                    repository = repository,
                    eligibilityRepository = eligibility,
                    adminRiskService = mockk(),
                    notAppealablePolicy = { _, _, _ -> true },
                    clock = clock,
                )

            val error =
                assertFailsWith<AppealException> {
                    // Non-account subject so eligibility risk-state is skipped; policy denies (e.g. CSAM).
                    service.submitAppeal(
                        userId,
                        SubmitAppealRequestDto(subjectType = "media", subjectRef = "asset-123", reason = "Reinstate this."),
                    )
                }

            assertEquals("APPEAL_NOT_ALLOWED", error.code)
            coVerify(exactly = 0) { repository.createAppeal(any(), any(), any(), any()) }
        }

    @Test
    fun approveAccountAppealRestoresAccountAndMarksApproved() =
        runBlocking {
            val repository = mockk<AppealRepository>()
            val adminRiskService = mockk<AdminRiskService>()
            coEvery { repository.getAppeal(appealId) } returns appealRecord(status = "pending")
            coEvery { adminRiskService.setRiskTier(userId.toString(), "clean", "moderator-9", any()) } returns
                AdminRiskActionRecord(
                    userId = userId,
                    score = 0,
                    tier = "clean",
                    lastReviewedAt = now,
                    updatedAt = now,
                )
            coEvery { repository.resolveAppeal(appealId, "approved", "moderator-9", any()) } returns
                appealRecord(status = "approved")
            val service =
                AppealService(
                    repository = repository,
                    eligibilityRepository = mockk(),
                    adminRiskService = adminRiskService,
                    clock = clock,
                )

            val result =
                service.resolve(
                    appealIdText = appealId.toString(),
                    request = AdminResolveAppealRequestDto(approve = true, reason = "Verified false positive."),
                    adminId = "moderator-9",
                )

            assertEquals("approved", result.status)
            coVerify(exactly = 1) { adminRiskService.setRiskTier(userId.toString(), "clean", "moderator-9", any()) }
            coVerify(exactly = 1) { repository.resolveAppeal(appealId, "approved", "moderator-9", any()) }
        }

    @Test
    fun denyAccountAppealMarksDeniedWithoutRestoring() =
        runBlocking {
            val repository = mockk<AppealRepository>()
            val adminRiskService = mockk<AdminRiskService>()
            coEvery { repository.getAppeal(appealId) } returns appealRecord(status = "pending")
            coEvery { repository.resolveAppeal(appealId, "denied", "moderator-9", any()) } returns
                appealRecord(status = "denied")
            val service =
                AppealService(
                    repository = repository,
                    eligibilityRepository = mockk(),
                    adminRiskService = adminRiskService,
                    clock = clock,
                )

            val result =
                service.resolve(
                    appealIdText = appealId.toString(),
                    request = AdminResolveAppealRequestDto(approve = false, reason = "Ban upheld after review."),
                    adminId = "moderator-9",
                )

            assertEquals("denied", result.status)
            coVerify(exactly = 0) { adminRiskService.setRiskTier(any(), any(), any(), any()) }
        }

    @Test
    fun resolveRejectsAlreadyResolvedAppeal() =
        runBlocking {
            val repository = mockk<AppealRepository>()
            coEvery { repository.getAppeal(appealId) } returns appealRecord(status = "approved")
            val service =
                AppealService(
                    repository = repository,
                    eligibilityRepository = mockk(),
                    adminRiskService = mockk(),
                    clock = clock,
                )

            val error =
                assertFailsWith<AppealException> {
                    service.resolve(
                        appealIdText = appealId.toString(),
                        request = AdminResolveAppealRequestDto(approve = true, reason = "again"),
                        adminId = "moderator-9",
                    )
                }

            assertEquals("APPEAL_NOT_PENDING", error.code)
        }

    private fun service(
        repository: AppealRepository,
        eligibility: AppealEligibilityRepository,
    ): AppealService {
        val adminRiskService = mockk<AdminRiskService>()
        coEvery { adminRiskService.setRiskTier(any(), any(), any(), any()) } returns
            AdminRiskActionRecord(userId = userId, score = 0, tier = "clean", lastReviewedAt = now, updatedAt = now)
        return AppealService(
            repository = repository,
            eligibilityRepository = eligibility,
            adminRiskService = adminRiskService,
            clock = clock,
        )
    }

    private fun appealRecord(status: String): AppealRecord =
        AppealRecord(
            id = appealId,
            userId = userId,
            subjectType = "account",
            subjectRef = null,
            status = status,
            createdAt = now.minus(Duration.ofDays(1)),
            resolvedAt = if (status == "pending") null else now,
            resolvedByAdminId = if (status == "pending") null else "moderator-9",
            resolutionNote = if (status == "pending") null else "note",
        )
}
