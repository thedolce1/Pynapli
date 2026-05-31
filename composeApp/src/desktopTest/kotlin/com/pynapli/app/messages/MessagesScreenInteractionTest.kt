package com.pynapli.app.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.PartnerChatInviteDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesScreenInteractionTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun messagesRowApprovesThirdPartyPartnerInvite() =
        runComposeUiTest {
            val repository = ThirdPartyInviteMessagesRepository()
            val stateHolder = MessagesStateHolder(repository)

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        MessagesScreen(
                            viewerUserId = "target",
                            onThreadSelected = {},
                            stateHolder = stateHolder,
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.listCount == 1 }
            onNodeWithText("Partner invite needs your approval").assertExists()
            onNodeWithText("Approval").assertExists()

            onNodeWithTag(MessagesTestTags.acceptInvite("conversation-1")).performClick()
            waitUntil(timeoutMillis = 5_000) { repository.acceptCount == 1 }

            assertEquals(
                ConversationParticipantStatus.ACTIVE,
                stateHolder.state.value.conversations
                    .single()
                    .participants[2]
                    .status,
            )
            onNodeWithText("No messages yet").assertExists()
        }
}

private class ThirdPartyInviteMessagesRepository : MessagesRepository {
    var listCount = 0
        private set
    var acceptCount = 0
        private set

    private var conversation = pendingThirdPartyApprovalConversation()

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> {
        listCount += 1
        return AppResult.Success(ConversationListResponseDto(items = listOf(conversation)))
    }

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> {
        acceptCount += 1
        conversation =
            conversation.copy(
                partnerInvite = null,
                participants =
                    conversation.participants.map { participant ->
                        if (participant.userId == "partner") {
                            participant.copy(status = ConversationParticipantStatus.ACTIVE)
                        } else {
                            participant
                        }
                    },
            )
        return AppResult.Success(ConversationDetailDto(conversation = conversation, messages = emptyList()))
    }

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED))
}

private fun pendingThirdPartyApprovalConversation(): ConversationDto =
    ConversationDto(
        conversationId = "conversation-1",
        kind = "group",
        origin = "grid",
        participants =
            listOf(
                ConversationParticipantDto("inviter", "Maya", status = ConversationParticipantStatus.ACTIVE),
                ConversationParticipantDto("target", "You", status = ConversationParticipantStatus.ACTIVE),
                ConversationParticipantDto(
                    userId = "partner",
                    displayName = "Rae",
                    status = ConversationParticipantStatus.INVITED,
                    invitedByUserId = "inviter",
                ),
            ),
        unreadCount = 0,
        updatedAt = "2026-05-22T14:00:00Z",
        partnerInvite =
            PartnerChatInviteDto(
                inviteId = "invite-1",
                inviterUserId = "inviter",
                invitedPartnerUserId = "partner",
                thirdPartyStatus = PartnerChatInviteStatus.PENDING,
                partnerStatus = PartnerChatInviteStatus.ACCEPTED,
                createdAt = "2026-05-22T14:00:00Z",
            ),
    )
