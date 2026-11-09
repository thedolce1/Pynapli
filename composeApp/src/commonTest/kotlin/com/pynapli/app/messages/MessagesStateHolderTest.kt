package com.pynapli.app.messages

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.PartnerChatInviteDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MessagesStateHolderTest {
    @Test
    fun partnerChatInvitesOnlyReturnsInvitedViewerConversations() {
        val invited = conversation("invited", viewerStatus = ConversationParticipantStatus.INVITED)
        val active = conversation("active", viewerStatus = ConversationParticipantStatus.ACTIVE)

        val invites = MessagesUiState(conversations = listOf(invited, active)).partnerChatInvites("viewer")

        assertEquals(listOf(invited), invites)
    }

    @Test
    fun acceptPartnerInviteUpdatesConversationStatus() =
        runTest {
            val repository =
                FakeMessagesRepository(
                    conversations = listOf(conversation("conversation-1", viewerStatus = ConversationParticipantStatus.INVITED)),
                )
            val stateHolder = MessagesStateHolder(repository)
            stateHolder.load()

            repository.acceptResult =
                AppResult.Success(
                    ConversationDetailDto(
                        conversation = conversation("conversation-1", viewerStatus = ConversationParticipantStatus.ACTIVE),
                        messages = emptyList(),
                    ),
                )
            stateHolder.acceptPartnerInvite("conversation-1")

            val updated =
                stateHolder.state.value.conversations
                    .single()
            assertEquals(ConversationParticipantStatus.ACTIVE, updated.viewerStatus())
            assertFalse("conversation-1" in stateHolder.state.value.mutatingPartnerInviteConversationIds)
        }

    @Test
    fun declinePartnerInviteRemovesConversation() =
        runTest {
            val repository =
                FakeMessagesRepository(
                    conversations = listOf(conversation("conversation-1", viewerStatus = ConversationParticipantStatus.INVITED)),
                )
            val stateHolder = MessagesStateHolder(repository)
            stateHolder.load()

            stateHolder.declinePartnerInvite("conversation-1", viewerUserId = "viewer")

            assertEquals(emptyList(), stateHolder.state.value.conversations)
            assertFalse("conversation-1" in stateHolder.state.value.mutatingPartnerInviteConversationIds)
        }

    @Test
    fun declineThirdPartyApprovalRefreshesThreadInsteadOfRemovingIt() =
        runTest {
            val pending =
                conversation(
                    conversationId = "conversation-1",
                    viewerStatus = ConversationParticipantStatus.ACTIVE,
                    partnerInvite =
                        PartnerChatInviteDto(
                            inviteId = "invite-1",
                            inviterUserId = "partner-owner",
                            invitedPartnerUserId = "partner",
                            thirdPartyStatus = PartnerChatInviteStatus.PENDING,
                            partnerStatus = PartnerChatInviteStatus.ACCEPTED,
                            createdAt = "2026-05-01T17:30:00Z",
                        ),
                )
            val afterDecline = pending.copy(partnerInvite = null)
            val repository =
                FakeMessagesRepository(conversations = listOf(pending)).apply {
                    onDecline = { listResult = listOf(afterDecline) }
                }
            val stateHolder = MessagesStateHolder(repository)
            stateHolder.load()

            stateHolder.declinePartnerInvite("conversation-1", viewerUserId = "viewer")

            assertEquals(listOf(afterDecline), stateHolder.state.value.conversations)
            assertFalse("conversation-1" in stateHolder.state.value.mutatingPartnerInviteConversationIds)
        }
}

private class FakeMessagesRepository(
    conversations: List<ConversationDto>,
) : MessagesRepository {
    var listResult: List<ConversationDto> = conversations
    var onDecline: () -> Unit = {}
    var acceptResult: AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(conversation = conversations.first(), messages = emptyList()),
        )
    var declineResult: AppResult<ConversationPartnerInviteResponseDto> =
        AppResult.Success(
            ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED),
        )

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> = AppResult.Success(ConversationListResponseDto(items = listResult))

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> = acceptResult

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> {
        onDecline()
        return declineResult
    }
}

private fun conversation(
    conversationId: String,
    viewerStatus: String,
    partnerInvite: PartnerChatInviteDto? = null,
): ConversationDto =
    ConversationDto(
        conversationId = conversationId,
        kind = "group",
        origin = "grid",
        participants =
            listOf(
                ConversationParticipantDto(
                    userId = "viewer",
                    displayName = "You",
                    status = viewerStatus,
                    invitedByUserId = if (viewerStatus == ConversationParticipantStatus.INVITED) "partner" else null,
                ),
                ConversationParticipantDto(
                    userId = "maya",
                    displayName = "Maya",
                    status = ConversationParticipantStatus.ACTIVE,
                ),
            ),
        unreadCount = 0,
        updatedAt = "2026-05-01T17:30:00Z",
        partnerInvite = partnerInvite,
    )

private fun ConversationDto.viewerStatus(): String? = participants.firstOrNull { it.userId == "viewer" }?.status
