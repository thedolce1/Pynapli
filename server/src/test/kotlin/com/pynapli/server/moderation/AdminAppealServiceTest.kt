package com.pynapli.server.moderation

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminAppealServiceTest {
    @Test
    fun listAppealsRejectsInvalidStatus() =
        runBlocking {
            val service = AdminAppealService(RecordingAdminAppealRepository())

            val error =
                assertFailsWith<ModerationException> {
                    service.listAppeals(
                        statusText = "waiting",
                        limitText = null,
                        actorId = "senior-mod-1",
                        reason = "appeal queue",
                    )
                }

            assertEquals("INVALID_APPEAL_REVIEW_STATUS", error.code)
        }

    @Test
    fun decideAppealRejectsInvalidOutcome() =
        runBlocking {
            val service = AdminAppealService(RecordingAdminAppealRepository())

            val error =
                assertFailsWith<ModerationException> {
                    service.decideAppeal(
                        appealIdText = UUID.randomUUID().toString(),
                        outcomeText = "delete",
                        actorId = "senior-mod-1",
                        reason = "review complete",
                        adminNote = null,
                    )
                }

            assertEquals("INVALID_APPEAL_OUTCOME", error.code)
        }

    @Test
    fun markUnderReviewPassesNormalizedInputs() =
        runBlocking {
            val appealId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val repository =
                RecordingAdminAppealRepository(
                    action =
                        AdminAppealActionRecord(
                            appealId = appealId,
                            userId = userId,
                            status = "under_review",
                            actedAt = Instant.parse("2026-05-02T12:00:00Z"),
                        ),
                )
            val service = AdminAppealService(repository)

            val action =
                service.markUnderReview(
                    appealIdText = appealId.toString(),
                    actorId = " senior-mod-1 ",
                    reason = " appeal review ",
                )

            assertEquals("under_review", action.status)
            assertEquals("senior-mod-1", repository.lastActorId)
            assertEquals("appeal review", repository.lastReason)
        }

    @Test
    fun decideAppealPassesNormalizedNote() =
        runBlocking {
            val appealId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val repository =
                RecordingAdminAppealRepository(
                    action =
                        AdminAppealActionRecord(
                            appealId = appealId,
                            userId = userId,
                            status = "restored",
                            actedAt = Instant.parse("2026-05-02T12:00:00Z"),
                        ),
                )
            val service = AdminAppealService(repository)

            val action =
                service.decideAppeal(
                    appealIdText = appealId.toString(),
                    outcomeText = " restored ",
                    actorId = " senior-mod-2 ",
                    reason = " false positive ",
                    adminNote = " We restored the account. ",
                )

            assertEquals("restored", action.status)
            assertEquals("senior-mod-2", repository.lastActorId)
            assertEquals("false positive", repository.lastReason)
            assertEquals("We restored the account.", repository.lastAdminNote)
        }
}

private class RecordingAdminAppealRepository(
    private val action: AdminAppealActionRecord? = null,
) : AdminAppealRepository {
    var lastActorId: String? = null
    var lastReason: String? = null
    var lastAdminNote: String? = null

    override suspend fun listAppeals(
        status: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminAppealRecord> {
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun markUnderReview(
        appealId: UUID,
        actorId: String,
        reason: String,
    ): AdminAppealActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun decideAppeal(
        appealId: UUID,
        status: String,
        actorId: String,
        reason: String,
        adminNote: String?,
    ): AdminAppealActionRecord? {
        lastActorId = actorId
        lastReason = reason
        lastAdminNote = adminNote
        return action
    }
}
