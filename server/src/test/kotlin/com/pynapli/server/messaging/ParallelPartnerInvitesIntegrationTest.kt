package com.pynapli.server.messaging

import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.PartnerChatInviteDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Several partner invites can be open in one conversation (MESSAGING_SPEC "Partner Invite Flow"): each
 * side invites their own partner, and every invite is answered on its own. Alex is linked with Jo,
 * Sam with Riley; Alex and Sam share a conversation.
 */
class ParallelPartnerInvitesIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val alex = UUID.fromString("00000000-0000-0000-0000-000000000d01")
    private val jo = UUID.fromString("00000000-0000-0000-0000-000000000d02")
    private val sam = UUID.fromString("00000000-0000-0000-0000-000000000d03")
    private val riley = UUID.fromString("00000000-0000-0000-0000-000000000d04")

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        dataSource.insertDiscoverableUser(alex, "Alex", 35, -79.38, 43.65)
        dataSource.insertDiscoverableUser(jo, "Jo", 34, -79.381, 43.651)
        dataSource.insertDiscoverableUser(sam, "Sam", 33, -79.382, 43.652)
        dataSource.insertDiscoverableUser(riley, "Riley", 32, -79.383, 43.653)
        listOf(alex to jo, jo to alex, sam to riley, riley to sam).forEach { (user, partner) ->
            dataSource.insertActiveProfileLink(userId = user, linkedUserId = partner)
        }
    }

    private fun service(): MessagingService =
        MessagingService(
            JdbcMessagingRepository(dataSource, contactBlockTokens = com.pynapli.server.contactblocks.ContactBlockTokens.localDevelopment()),
            blockRepository = JdbcBlockRepository(dataSource),
        )

    private suspend fun MessagingService.conversation(): String =
        createDirectConversation(
            alex,
            CreateDirectConversationRequestDto(targetUserId = sam.toString(), origin = "grid"),
        ).conversation.conversationId

    /** Both sides invite their own partner; returns the conversation id. */
    private suspend fun MessagingService.bothInvited(): String {
        val conversationId = conversation()
        inviteLinkedPartnerToConversation(alex, conversationId)
        inviteLinkedPartnerToConversation(sam, conversationId)
        return conversationId
    }

    private suspend fun MessagingService.invites(
        viewer: UUID,
        conversationId: String,
    ): List<PartnerChatInviteDto> = getConversationDetail(viewer, conversationId).conversation.partnerInvites

    private suspend fun MessagingService.inviteFor(
        partner: UUID,
        conversationId: String,
    ): PartnerChatInviteDto? = invites(alex, conversationId).singleOrNull { it.invitedPartnerUserId == partner.toString() }

    private suspend fun MessagingService.statusOf(
        user: UUID,
        conversationId: String,
    ): String? =
        getConversationDetail(alex, conversationId)
            .conversation
            .participants
            .singleOrNull { it.userId == user.toString() }
            ?.status

    @Test
    fun bothSidesCanInviteTheirOwnPartnerAtOnceAndEachSeesTheRightInvites() =
        runBlocking {
            val service = service()
            val conversationId = service.bothInvited()

            // Active participants see both; each invited partner sees only their own.
            assertEquals(2, service.invites(alex, conversationId).size)
            assertEquals(2, service.invites(sam, conversationId).size)
            assertEquals(listOf(jo.toString()), service.invites(jo, conversationId).map { it.invitedPartnerUserId })
            assertEquals(listOf(riley.toString()), service.invites(riley, conversationId).map { it.invitedPartnerUserId })
            // The single field older clients read is the invite each person answers.
            assertEquals(
                jo.toString(),
                service
                    .getConversationDetail(jo, conversationId)
                    .conversation.partnerInvite
                    ?.invitedPartnerUserId,
            )
            assertEquals(
                jo.toString(),
                service
                    .getConversationDetail(sam, conversationId)
                    .conversation.partnerInvite
                    ?.invitedPartnerUserId,
            )
            assertEquals(
                riley.toString(),
                service
                    .getConversationDetail(alex, conversationId)
                    .conversation.partnerInvite
                    ?.invitedPartnerUserId,
            )
        }

    @Test
    fun theEarlierInviteeCanAnswerWhileALaterInviteIsOpen() =
        runBlocking {
            val service = service()
            val conversationId = service.bothInvited()

            // Before: Jo's accept resolved against Riley's newer invite and failed.
            service.acceptPartnerInvite(jo, conversationId)
            assertEquals(PartnerChatInviteStatus.ACCEPTED, service.inviteFor(jo, conversationId)?.partnerStatus)
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(riley, conversationId)?.partnerStatus)
        }

    @Test
    fun everyoneAnswersTheirOwnInviteInEitherOrderAndBothPartnersJoin() =
        runBlocking {
            val service = service()
            val conversationId = service.bothInvited()
            val joInvite = requireNotNull(service.inviteFor(jo, conversationId)).inviteId
            val rileyInvite = requireNotNull(service.inviteFor(riley, conversationId)).inviteId

            // Approvals first, each naming its invite; then the partners, later invitee first.
            service.acceptPartnerInvite(alex, conversationId, rileyInvite)
            service.acceptPartnerInvite(sam, conversationId, joInvite)
            service.acceptPartnerInvite(riley, conversationId)
            assertEquals(ConversationParticipantStatus.ACTIVE, service.statusOf(riley, conversationId))
            assertEquals(ConversationParticipantStatus.INVITED, service.statusOf(jo, conversationId))
            service.acceptPartnerInvite(jo, conversationId)

            assertEquals(ConversationParticipantStatus.ACTIVE, service.statusOf(jo, conversationId))
            assertEquals(emptyList<PartnerChatInviteDto>(), service.invites(alex, conversationId))
            assertEquals("group", service.getConversationDetail(alex, conversationId).conversation.kind)
        }

    @Test
    fun aThirdPartyApprovalTargetsTheNamedInviteOnly() =
        runBlocking {
            val service = service()
            val conversationId = service.bothInvited()
            val rileyInvite = requireNotNull(service.inviteFor(riley, conversationId)).inviteId
            val joInvite = requireNotNull(service.inviteFor(jo, conversationId)).inviteId

            service.acceptPartnerInvite(alex, conversationId, rileyInvite)

            assertEquals(PartnerChatInviteStatus.ACCEPTED, service.inviteFor(riley, conversationId)?.thirdPartyStatus)
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(jo, conversationId)?.thirdPartyStatus)
            // Nobody approves their own invite of their own partner.
            val refusal = assertFailsWith<MessagingException> { service.acceptPartnerInvite(alex, conversationId, joInvite) }
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusal.code)
        }

    @Test
    fun anInvitedPersonCannotApproveOrDeclineAnotherInvite() =
        runBlocking {
            val service = service()
            val conversationId = service.bothInvited()
            val rileyInvite = requireNotNull(service.inviteFor(riley, conversationId)).inviteId

            val acceptRefusal =
                assertFailsWith<MessagingException> { service.acceptPartnerInvite(jo, conversationId, rileyInvite) }
            assertEquals("PARTNER_INVITE_NOT_FOUND", acceptRefusal.code)
            val declineRefusal =
                assertFailsWith<MessagingException> { service.declinePartnerInvite(jo, conversationId, rileyInvite) }
            assertEquals("PARTNER_INVITE_NOT_FOUND", declineRefusal.code)

            val untouched = requireNotNull(service.inviteFor(riley, conversationId))
            assertEquals(PartnerChatInviteStatus.PENDING, untouched.thirdPartyStatus)
            assertEquals(PartnerChatInviteStatus.PENDING, untouched.partnerStatus)
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(jo, conversationId)?.partnerStatus)
        }

    @Test
    fun oneDeclineLeavesTheOtherInvitePending() =
        runBlocking {
            val service = service()
            val conversationId = service.bothInvited()
            val joInvite = requireNotNull(service.inviteFor(jo, conversationId)).inviteId

            service.declinePartnerInvite(riley, conversationId)
            assertNull(service.inviteFor(riley, conversationId))
            assertEquals(ConversationParticipantStatus.ACTIVE, service.statusOf(alex, conversationId))
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(jo, conversationId)?.partnerStatus)

            // A third-party decline of Jo's invite starts Jo's cooldown only.
            service.declinePartnerInvite(sam, conversationId, joInvite)
            assertEquals(emptyList<PartnerChatInviteDto>(), service.invites(alex, conversationId))
            val cooldown = assertFailsWith<MessagingException> { service.inviteLinkedPartnerToConversation(alex, conversationId) }
            assertEquals("PARTNER_INVITE_COOLDOWN", cooldown.code)
            // Riley's own decline starts the same cooldown: Sam cannot re-invite Riley straight away.
            val rileyCooldown =
                assertFailsWith<MessagingException> { service.inviteLinkedPartnerToConversation(sam, conversationId) }
            assertEquals("PARTNER_INVITE_COOLDOWN", rileyCooldown.code)
        }

    @Test
    fun anOlderClientWithoutAnInviteIdStillWorksWithOneOpenInvite() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)

            service.acceptPartnerInvite(sam, conversationId)
            service.acceptPartnerInvite(jo, conversationId)

            assertEquals(ConversationParticipantStatus.ACTIVE, service.statusOf(jo, conversationId))
            assertNull(service.getConversationDetail(alex, conversationId).conversation.partnerInvite)
        }

    @Test
    fun anOlderClientApproverWithoutAnInviteIdAnswersTheInviteItIsShown() =
        runBlocking {
            val service = service()
            val conversationId = service.bothInvited()
            val shown = requireNotNull(service.getConversationDetail(sam, conversationId).conversation.partnerInvite)

            service.acceptPartnerInvite(sam, conversationId)

            assertEquals(jo.toString(), shown.invitedPartnerUserId)
            assertEquals(PartnerChatInviteStatus.ACCEPTED, service.inviteFor(jo, conversationId)?.thirdPartyStatus)
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(riley, conversationId)?.thirdPartyStatus)
        }
}
