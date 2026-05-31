package com.pynapli.server.moderation

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageModerationServiceTest {
    @Test
    fun listOpenMessageReviewsRejectsInvalidLimit() =
        runBlocking {
            val service = MessageModerationService(RecordingMessageModerationRepository())

            val error =
                assertFailsWith<ModerationException> {
                    service.listOpenMessageReviews(
                        limitText = "500",
                        actorId = "moderator-1",
                        reason = "review",
                    )
                }

            assertEquals("INVALID_REVIEW_LIMIT", error.code)
        }

    @Test
    fun releaseHeldMessageRequiresReason() =
        runBlocking {
            val service = MessageModerationService(RecordingMessageModerationRepository())

            val error =
                assertFailsWith<ModerationException> {
                    service.releaseHeldMessage(
                        messageIdText = UUID.randomUUID().toString(),
                        actorId = "moderator-1",
                        reason = " ",
                    )
                }

            assertEquals("MODERATION_REASON_REQUIRED", error.code)
        }

    @Test
    fun removeHeldMessageReturnsNotFoundWhenRepositoryMisses() =
        runBlocking {
            val service = MessageModerationService(RecordingMessageModerationRepository())

            val error =
                assertFailsWith<ModerationException> {
                    service.removeHeldMessage(
                        messageIdText = UUID.randomUUID().toString(),
                        actorId = "moderator-1",
                        reason = "spam",
                    )
                }

            assertEquals("MESSAGE_REVIEW_NOT_FOUND", error.code)
        }

    @Test
    fun releaseHeldMessageReturnsRepositoryAction() =
        runBlocking {
            val messageId = UUID.randomUUID()
            val repository =
                RecordingMessageModerationRepository(
                    releaseAction =
                        MessageReviewActionRecord(
                            messageId = messageId,
                            conversationId = UUID.randomUUID(),
                            senderUserId = UUID.randomUUID(),
                            messageStatus = "visible",
                            queueStatus = "released",
                            reviewedAt = Instant.parse("2026-04-24T18:00:00Z"),
                        ),
                )
            val service = MessageModerationService(repository)

            val action =
                service.releaseHeldMessage(
                    messageIdText = messageId.toString(),
                    actorId = "moderator-1",
                    reason = "false positive",
                )

            assertEquals("visible", action.messageStatus)
            assertEquals("moderator-1", repository.lastActorId)
            assertEquals("false positive", repository.lastReason)
        }
}

private class RecordingMessageModerationRepository(
    private val releaseAction: MessageReviewActionRecord? = null,
    private val removeAction: MessageReviewActionRecord? = null,
) : MessageModerationRepository {
    var lastActorId: String? = null
    var lastReason: String? = null

    override suspend fun listOpenMessageReviews(
        limit: Int,
        actorId: String,
        reason: String,
    ): List<MessageReviewRecord> {
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun releaseHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
    ): MessageReviewActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return releaseAction
    }

    override suspend fun removeHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
    ): MessageReviewActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return removeAction
    }
}
