package com.pynapli.server.safety

import com.pynapli.shared.network.dto.CreateAppealRequestDto
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppealServiceTest {
    @Test
    fun submitAppealPersistsValidRiskRestrictionAppeal() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000a01")
            val repository = InMemoryAppealRepository()
            val service = AppealService(repository)

            val appeal =
                service.submitAppeal(
                    userId,
                    CreateAppealRequestDto(
                        actionType = " risk_restriction ",
                        targetType = " account ",
                        reason = "This looks like a false positive.",
                    ),
                )

            assertEquals("risk_restriction", appeal.actionType)
            assertEquals("account", appeal.targetType)
            assertEquals("open", appeal.status)
            assertEquals("This looks like a false positive.", repository.created.single().userReason)
        }

    @Test
    fun submitAppealRejectsMismatchedTarget() =
        runBlocking {
            val service = AppealService(InMemoryAppealRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.submitAppeal(
                        UUID.randomUUID(),
                        CreateAppealRequestDto(
                            actionType = "media_removal",
                            targetType = "account",
                            reason = "Wrong target",
                        ),
                    )
                }

            assertEquals("INVALID_APPEAL_TARGET", error.code)
        }

    @Test
    fun submitAppealRejectsOversizedReason() =
        runBlocking {
            val service = AppealService(InMemoryAppealRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.submitAppeal(
                        UUID.randomUUID(),
                        CreateAppealRequestDto(
                            actionType = "other",
                            targetType = "other",
                            reason = "x".repeat(2_001),
                        ),
                    )
                }

            assertEquals("APPEAL_REASON_TOO_LONG", error.code)
        }

    @Test
    fun submitAppealMapsDuplicateOpenAppeal() =
        runBlocking {
            val service = AppealService(InMemoryAppealRepository(createResult = CreateAppealResult.DuplicateOpenAppeal))

            val error =
                assertFailsWith<SafetyException> {
                    service.submitAppeal(
                        UUID.randomUUID(),
                        CreateAppealRequestDto(
                            actionType = "other",
                            targetType = "other",
                            reason = "Please review.",
                        ),
                    )
                }

            assertEquals("APPEAL_ALREADY_OPEN", error.code)
        }

    @Test
    fun withdrawAppealReturnsNotFoundWhenRepositoryMisses() =
        runBlocking {
            val service = AppealService(InMemoryAppealRepository())

            val error =
                assertFailsWith<SafetyException> {
                    service.withdrawAppeal(UUID.randomUUID(), UUID.randomUUID().toString())
                }

            assertEquals("APPEAL_NOT_FOUND", error.code)
        }
}

private class InMemoryAppealRepository(
    private val createResult: CreateAppealResult? = null,
) : AppealRepository {
    val created = mutableListOf<AppealRecord>()
    private val now = Instant.parse("2026-05-01T12:00:00Z")

    override suspend fun createAppeal(
        userId: UUID,
        actionType: String,
        targetType: String,
        targetId: String?,
        relatedReportId: UUID?,
        reason: String,
        appealIdFactory: () -> UUID,
    ): CreateAppealResult {
        createResult?.let { return it }
        val appeal =
            AppealRecord(
                id = appealIdFactory(),
                userId = userId,
                actionType = actionType,
                targetType = targetType,
                targetId = targetId,
                relatedReportId = relatedReportId,
                status = "open",
                userReason = reason,
                adminNote = null,
                createdAt = now,
                updatedAt = now,
                reviewedAt = null,
            )
        created += appeal
        return CreateAppealResult.Created(appeal)
    }

    override suspend fun listAppeals(
        userId: UUID,
        limit: Int,
    ): List<AppealRecord> = created.take(limit)

    override suspend fun withdrawAppeal(
        userId: UUID,
        appealId: UUID,
    ): AppealRecord? = null
}
