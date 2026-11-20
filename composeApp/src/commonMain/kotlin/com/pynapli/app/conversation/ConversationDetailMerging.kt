package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.MessageDto

internal fun ConversationDetailDto.withAppendedMessage(message: MessageDto): ConversationDetailDto =
    copy(
        conversation =
            conversation.copy(
                lastMessage = message,
                unreadCount = 0,
                updatedAt = message.createdAt,
            ),
        // Idempotent: a refetch can deliver the sent message before the send call returns. A
        // duplicate id would show the bubble twice and crash the keyed thread list.
        messages =
            if (messages.any { existing -> existing.messageId == message.messageId }) {
                messages.map { existing -> if (existing.messageId == message.messageId) message else existing }
            } else {
                messages + message
            },
    )

internal fun ConversationDetailDto.withUpdatedMessage(message: MessageDto): ConversationDetailDto =
    copy(
        conversation =
            conversation.copy(
                lastMessage =
                    if (conversation.lastMessage?.messageId == message.messageId) {
                        message
                    } else {
                        conversation.lastMessage
                    },
            ),
        messages =
            messages.map { existing ->
                if (existing.messageId == message.messageId) message else existing
            },
    )

internal suspend fun ConversationDetailDto.withReadConversation(
    repository: ConversationRepository,
): ConversationDetailDto =
    when (val readResult = repository.markConversationRead(conversation.conversationId)) {
        is AppResult.Success -> copy(conversation = readResult.value)
        is AppResult.Failure -> this
    }

internal fun ConversationDetailDto.withPrependedPage(page: ConversationDetailDto): ConversationDetailDto =
    copy(
        // An older page is a plain detail read; the offer and cooldown come with the read mark, so keep them.
        conversation =
            page.conversation.copy(
                partnerOffers = page.conversation.partnerOffers ?: conversation.partnerOffers,
                ownPartnerInviteCoolingDown =
                    page.conversation.ownPartnerInviteCoolingDown ?: conversation.ownPartnerInviteCoolingDown,
                metInPersonOffers = page.conversation.metInPersonOffers ?: conversation.metInPersonOffers,
            ),
        messages = mergeMessages(page.messages + messages),
        nextCursor = page.nextCursor,
    )

internal fun ConversationDetailDto.withMergedMessagesFrom(existing: ConversationDetailDto): ConversationDetailDto =
    copy(
        messages = mergeMessages(existing.messages + messages),
        nextCursor = existing.nextCursor ?: nextCursor,
    )

private fun mergeMessages(messages: List<MessageDto>): List<MessageDto> =
    messages
        .associateBy { message -> message.messageId }
        .values
        .sortedWith(compareBy<MessageDto> { message -> message.createdAt }.thenBy { message -> message.messageId })
