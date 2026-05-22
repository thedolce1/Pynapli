package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.MessageDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationStateHolderTest {
    @Test
    fun deleteOwnMessageReplacesBodyWithPlaceholderState() = runBlocking {
        val repository = FakeConversationRepository()
        val stateHolder = ConversationStateHolder(repository)
        stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

        stateHolder.deleteMessage("message-1")

        val message = stateHolder.state.value.detail?.messages?.single()
        assertEquals("message-1", repository.deletedMessageId)
        assertEquals("", message?.body)
        assertEquals("deleted", message?.deliveryStatus)
        assertEquals("2026-05-22T18:01:00Z", message?.deletedAt)
        assertNull(stateHolder.state.value.errorMessage)
    }

    @Test
    fun deleteMessageIgnoresMessageFromOtherParticipant() = runBlocking {
        val repository = FakeConversationRepository(
            initialMessage = message(senderUserId = "target")
        )
        val stateHolder = ConversationStateHolder(repository)
        stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

        stateHolder.deleteMessage("message-1")

        assertNull(repository.deletedMessageId)
        assertEquals("Original", stateHolder.state.value.detail?.messages?.single()?.body)
    }
}

private class FakeConversationRepository(
    private val initialMessage: MessageDto = message(senderUserId = "viewer")
) : ConversationRepository {
    val conversation = ConversationDto(
        conversationId = "conversation-1",
        kind = "direct",
        origin = "grid",
        participants = listOf(
            ConversationParticipantDto("viewer", "You"),
            ConversationParticipantDto("target", "Maya")
        ),
        lastMessage = initialMessage,
        unreadCount = 0,
        updatedAt = "2026-05-22T18:00:00Z"
    )
    var deletedMessageId: String? = null

    override suspend fun createDirectConversation(targetUserId: String, origin: String): AppResult<ConversationDetailDto> =
        AppResult.Failure(AppError("UNUSED", "Unused."))

    override suspend fun getConversationDetail(conversationId: String, before: String?): AppResult<ConversationDetailDto> =
        AppResult.Success(ConversationDetailDto(conversation = conversation, messages = listOf(initialMessage)))

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
        AppResult.Success(conversation.copy(unreadCount = 0))

    override suspend fun sendMessage(conversationId: String, body: String): AppResult<MessageDto> =
        AppResult.Failure(AppError("UNUSED", "Unused."))

    override suspend fun deleteMessage(messageId: String): AppResult<MessageDto> {
        deletedMessageId = messageId
        return AppResult.Success(
            initialMessage.copy(
                body = "",
                deliveryStatus = "deleted",
                deletedAt = "2026-05-22T18:01:00Z"
            )
        )
    }

    override suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto> =
        AppResult.Failure(AppError("UNUSED", "Unused."))

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> =
        AppResult.Failure(AppError("UNUSED", "Unused."))

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED))

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.LEFT))
}

private fun message(senderUserId: String): MessageDto =
    MessageDto(
        messageId = "message-1",
        conversationId = "conversation-1",
        senderUserId = senderUserId,
        body = "Original",
        createdAt = "2026-05-22T18:00:00Z",
        status = "visible",
        deliveryStatus = "sent"
    )
