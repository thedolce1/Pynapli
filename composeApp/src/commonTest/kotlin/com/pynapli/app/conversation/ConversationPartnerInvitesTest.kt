package com.pynapli.app.conversation

import com.pynapli.app.messages.partnerInviteUiState
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.PartnerChatInviteDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Several open partner invites: each person answers the right one; older servers send one. */
class ConversationPartnerInvitesTest {
    private val joInvite = invite(id = "i-jo", inviter = "alex", invitee = "jo")
    private val rileyInvite = invite(id = "i-riley", inviter = "sam", invitee = "riley")

    @Test
    fun eachInviteeAnswersTheirOwnInvite() {
        val conversation = twoInvites()

        assertEquals("i-jo", conversation.actionablePartnerInvite("jo")?.inviteId)
        assertEquals("i-riley", conversation.actionablePartnerInvite("riley")?.inviteId)
    }

    @Test
    fun eachApproverAnswersTheOtherSidesInviteNeverTheirOwn() {
        val conversation = twoInvites()

        assertEquals("i-riley", conversation.actionablePartnerInvite("alex")?.inviteId)
        assertEquals("i-jo", conversation.actionablePartnerInvite("sam")?.inviteId)
    }

    @Test
    fun anInvitedPersonApprovesNothingOnceTheyHaveAnswered() {
        val conversation =
            twoInvites(joInvite = joInvite.copy(partnerStatus = PartnerChatInviteStatus.ACCEPTED))

        assertNull(conversation.actionablePartnerInvite("jo"))
    }

    @Test
    fun anOlderServerWithOnlyTheSingleFieldStillWorks() {
        val conversation = twoInvites().copy(partnerInvites = emptyList(), partnerInvite = joInvite)

        assertEquals(listOf(joInvite), conversation.openPartnerInvites())
        assertEquals("i-jo", conversation.actionablePartnerInvite("sam")?.inviteId)
        assertNull(conversation.actionablePartnerInvite("alex"))
    }

    @Test
    fun theInboxRowSpeaksForTheInviteTheViewerAnswers() {
        val conversation = twoInvites()

        assertEquals("Partner invite needs your approval", conversation.partnerInviteUiState("alex")?.preview)
        assertEquals("You were invited to join this chat", conversation.partnerInviteUiState("jo")?.preview)
    }

    private fun twoInvites(joInvite: PartnerChatInviteDto = this.joInvite): ConversationDto =
        ConversationDto(
            conversationId = "c1",
            kind = "group",
            origin = "swipe",
            participants =
                listOf(
                    ConversationParticipantDto(userId = "alex", displayName = "Alex"),
                    ConversationParticipantDto(userId = "sam", displayName = "Sam"),
                    ConversationParticipantDto(userId = "jo", displayName = "Jo", status = ConversationParticipantStatus.INVITED),
                    ConversationParticipantDto(userId = "riley", displayName = "Riley", status = ConversationParticipantStatus.INVITED),
                ),
            unreadCount = 0,
            updatedAt = "2026-11-19T10:00:00Z",
            partnerInvite = rileyInvite,
            partnerInvites = listOf(rileyInvite, joInvite),
        )

    private fun invite(
        id: String,
        inviter: String,
        invitee: String,
    ): PartnerChatInviteDto =
        PartnerChatInviteDto(
            inviteId = id,
            inviterUserId = inviter,
            invitedPartnerUserId = invitee,
            thirdPartyStatus = PartnerChatInviteStatus.PENDING,
            partnerStatus = PartnerChatInviteStatus.PENDING,
            createdAt = "2026-11-19T10:00:00Z",
        )
}
