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
    fun olderThreadsLoadPageByPageAndARefreshKeepsThePagesAlreadyLoaded() =
        runTest {
            val page1 = listOf(conversation("c1", ConversationParticipantStatus.ACTIVE, updatedAt = "2026-05-03T00:00:00Z"))
            val page2 = listOf(conversation("c2", ConversationParticipantStatus.ACTIVE, updatedAt = "2026-05-02T00:00:00Z"))
            val repository = FakeMessagesRepository(page1)
            repository.firstPageCursor = "after-c1"
            repository.pagesAfter = mapOf("after-c1" to ConversationListResponseDto(items = page2, nextCursor = null))
            val holder = MessagesStateHolder(repository)

            holder.load()
            assertEquals("after-c1", holder.state.value.nextCursor)
            holder.loadMore()
            assertEquals(
                listOf("c1", "c2"),
                holder.state.value.conversations
                    .map { it.conversationId },
            )
            assertEquals(null, holder.state.value.nextCursor)

            // A new message bumps a fresh thread to the top; the second page must not vanish.
            repository.listResult = listOf(conversation("c0", ConversationParticipantStatus.ACTIVE, updatedAt = "2026-05-04T00:00:00Z"))
            holder.refreshSilently()
            assertEquals(
                listOf("c0", "c1", "c2"),
                holder.state.value.conversations
                    .map { it.conversationId },
            )
        }

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

    var firstPageCursor: String? = null
    var pagesAfter: Map<String, ConversationListResponseDto> = emptyMap()

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> = AppResult.Success(ConversationListResponseDto(items = listResult, nextCursor = firstPageCursor))

    override suspend fun listConversationsAfter(cursor: String): AppResult<ConversationListResponseDto> = AppResult.Success(pagesAfter.getValue(cursor))

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
    updatedAt: String = "2026-05-01T17:30:00Z",
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
        updatedAt = updatedAt,
        partnerInvite = partnerInvite,
    )

private fun ConversationDto.viewerStatus(): String? = participants.firstOrNull { it.userId == "viewer" }?.status
