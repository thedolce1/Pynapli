package com.pynapli.app.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.PartnerChatInviteDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesScreenInteractionTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun messagesHeaderDoesNotShowGlobalDiscoverySegments() =
        runComposeUiTest {
            val repository = StaticMessagesRepository(emptyList())
            val stateHolder = MessagesStateHolder(repository)

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        MessagesScreen(
                            viewerUserId = "viewer",
                            onThreadSelected = {},
                            stateHolder = stateHolder,
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.listCount == 1 }
            onNodeWithText("Messages").assertExists()
            onNodeWithText("Discover").assertDoesNotExist()
            onNodeWithText("Linked").assertDoesNotExist()
            onNodeWithText("Tonight").assertDoesNotExist()
        }

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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun newSwipeMatchesShowInRailAndOpenOnTap() =
        runComposeUiTest {
            val matchConversation =
                ConversationDto(
                    conversationId = "match-conv",
                    kind = "direct",
                    origin = "swipe",
                    participants =
                        listOf(
                            ConversationParticipantDto("viewer", "You", status = ConversationParticipantStatus.ACTIVE),
                            ConversationParticipantDto("nico", "Nico", status = ConversationParticipantStatus.ACTIVE),
                        ),
                    lastMessage = null,
                    unreadCount = 0,
                    updatedAt = "2026-05-22T14:00:00Z",
                )
            val gridConversation =
                ConversationDto(
                    conversationId = "grid-conv",
                    kind = "direct",
                    origin = "grid",
                    participants =
                        listOf(
                            ConversationParticipantDto("viewer", "You", status = ConversationParticipantStatus.ACTIVE),
                            ConversationParticipantDto("maya", "Maya", status = ConversationParticipantStatus.ACTIVE),
                        ),
                    lastMessage = null,
                    unreadCount = 0,
                    updatedAt = "2026-05-22T13:00:00Z",
                )
            val repository = StaticMessagesRepository(listOf(matchConversation, gridConversation))
            val stateHolder = MessagesStateHolder(repository)
            val opened = mutableListOf<String>()

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        MessagesScreen(
                            viewerUserId = "viewer",
                            onThreadSelected = { opened += it.conversationId },
                            stateHolder = stateHolder,
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.listCount == 1 }
            onNodeWithTag(NewMatchesRailTags.RAIL).assertExists()
            // The new match rides the rail and is kept out of the conversation list.
            onNodeWithTag(NewMatchesRailTags.avatar("match-conv")).assertExists()
            onNodeWithTag(MessagesTestTags.row("match-conv")).assertDoesNotExist()
            onNodeWithTag(MessagesTestTags.row("grid-conv")).assertExists()

            onNodeWithTag(NewMatchesRailTags.avatar("match-conv")).performClick()
            waitUntil(timeoutMillis = 5_000) { opened == listOf("match-conv") }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun railOnlyInboxShowsSayHiHint() =
        runComposeUiTest {
            val matchConversation =
                ConversationDto(
                    conversationId = "match-conv",
                    kind = "direct",
                    origin = "swipe",
                    participants =
                        listOf(
                            ConversationParticipantDto("viewer", "You", status = ConversationParticipantStatus.ACTIVE),
                            ConversationParticipantDto("nico", "Nico", status = ConversationParticipantStatus.ACTIVE),
                        ),
                    lastMessage = null,
                    unreadCount = 0,
                    updatedAt = "2026-05-22T14:00:00Z",
                )
            val repository = StaticMessagesRepository(listOf(matchConversation))
            val stateHolder = MessagesStateHolder(repository)

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        MessagesScreen(
                            viewerUserId = "viewer",
                            onThreadSelected = {},
                            stateHolder = stateHolder,
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.listCount == 1 }
            onNodeWithTag(NewMatchesRailTags.RAIL).assertExists()
            onNodeWithText("Say hi to your new matches to start a conversation.").assertExists()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aMatchWithChatHistoryRidesTheRailWithoutSayHiAndOpensOnTap() =
        runComposeUiTest {
            // They talked from the Grid, then matched: the server flags it, though it has messages.
            val matchConversation =
                ConversationDto(
                    conversationId = "grid-match-conv",
                    kind = "direct",
                    origin = "grid",
                    participants =
                        listOf(
                            ConversationParticipantDto("viewer", "You", status = ConversationParticipantStatus.ACTIVE),
                            ConversationParticipantDto("nico", "Nico", status = ConversationParticipantStatus.ACTIVE),
                        ),
                    lastMessage =
                        MessageDto(
                            messageId = "m1",
                            conversationId = "grid-match-conv",
                            senderUserId = "nico",
                            body = "Hey",
                            createdAt = "2026-05-22T13:00:00Z",
                            status = "visible",
                            deliveryStatus = "sent",
                        ),
                    unreadCount = 0,
                    updatedAt = "2026-05-22T14:00:00Z",
                    newMatch = true,
                )
            val repository = StaticMessagesRepository(listOf(matchConversation))
            val stateHolder = MessagesStateHolder(repository)
            val opened = mutableListOf<String>()

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        MessagesScreen(
                            viewerUserId = "viewer",
                            onThreadSelected = { opened += it.conversationId },
                            stateHolder = stateHolder,
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.listCount == 1 }
            onNodeWithTag(NewMatchesRailTags.avatar("grid-match-conv")).assertExists()
            onNodeWithTag(MessagesTestTags.row("grid-match-conv")).assertDoesNotExist()
            onNodeWithText("Say hi to your new matches to start a conversation.").assertDoesNotExist()
            onNodeWithText(NewMatchesHint.OPEN).assertExists()

            onNodeWithTag(NewMatchesRailTags.avatar("grid-match-conv")).performClick()
            waitUntil(timeoutMillis = 5_000) { opened == listOf("grid-match-conv") }
        }
}

private class StaticMessagesRepository(
    private val conversations: List<ConversationDto>,
) : MessagesRepository {
    var listCount = 0
        private set

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> {
        listCount += 1
        return AppResult.Success(ConversationListResponseDto(items = conversations))
    }

    override suspend fun acceptPartnerInvite(
        conversationId: String,
        inviteId: String?,
    ): AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(conversation = conversations.first { it.conversationId == conversationId }, messages = emptyList()),
        )

    override suspend fun declinePartnerInvite(
        conversationId: String,
        inviteId: String?,
    ): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED))
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

    override suspend fun acceptPartnerInvite(
        conversationId: String,
        inviteId: String?,
    ): AppResult<ConversationDetailDto> {
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

    override suspend fun declinePartnerInvite(
        conversationId: String,
        inviteId: String?,
    ): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED))
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
