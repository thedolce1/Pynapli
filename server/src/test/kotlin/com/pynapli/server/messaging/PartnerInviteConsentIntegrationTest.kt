package com.pynapli.server.messaging

import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertBlock
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.raceTogether
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
import kotlin.test.assertTrue

/**
 * Consent and privacy of partner invites when several are open (MESSAGING_SPEC "Several invites at
 * once"): an acceptance never carries over to a changed group, an invitee never joins people they are
 * blocked with, an id-less retry never approves someone else, an invited person sees only who is in,
 * and a decline by either side starts the cooldown. Alex is linked with Jo, Sam with Riley.
 */
class PartnerInviteConsentIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val alex = UUID.fromString("00000000-0000-0000-0000-000000000e01")
    private val jo = UUID.fromString("00000000-0000-0000-0000-000000000e02")
    private val sam = UUID.fromString("00000000-0000-0000-0000-000000000e03")
    private val riley = UUID.fromString("00000000-0000-0000-0000-000000000e04")
    private val kim = UUID.fromString("00000000-0000-0000-0000-000000000e05")

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        dataSource.insertDiscoverableUser(alex, "Alex", 35, -79.38, 43.65)
        dataSource.insertDiscoverableUser(jo, "Jo", 34, -79.381, 43.651)
        dataSource.insertDiscoverableUser(sam, "Sam", 33, -79.382, 43.652)
        dataSource.insertDiscoverableUser(riley, "Riley", 32, -79.383, 43.653)
        dataSource.insertDiscoverableUser(kim, "Kim", 31, -79.384, 43.654)
        link(alex, jo)
        link(sam, riley)
    }

    private fun service(): MessagingService =
        MessagingService(
            JdbcMessagingRepository(dataSource, contactBlockTokens = com.pynapli.server.contactblocks.ContactBlockTokens.localDevelopment()),
            blockRepository = JdbcBlockRepository(dataSource),
        )

    private fun link(
        userA: UUID,
        userB: UUID,
    ) {
        dataSource.insertActiveProfileLink(userId = userA, linkedUserId = userB)
        dataSource.insertActiveProfileLink(userId = userB, linkedUserId = userA)
    }

    private fun sql(statementText: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(statementText) }
        }
    }

    private suspend fun MessagingService.conversation(
        first: UUID = alex,
        second: UUID = sam,
    ): String =
        createDirectConversation(
            first,
            CreateDirectConversationRequestDto(targetUserId = second.toString(), origin = "grid"),
        ).conversation.conversationId

    private suspend fun MessagingService.inviteFor(
        partner: UUID,
        conversationId: String,
        viewer: UUID = alex,
    ): PartnerChatInviteDto? {
        val invites = getConversationDetail(viewer, conversationId).conversation.partnerInvites
        return invites.singleOrNull { it.invitedPartnerUserId == partner.toString() }
    }

    private suspend fun MessagingService.inviteIdFor(
        partner: UUID,
        conversationId: String,
    ): String = requireNotNull(inviteFor(partner, conversationId)).inviteId

    private suspend fun MessagingService.statusOf(
        user: UUID,
        conversationId: String,
        viewer: UUID = alex,
    ): String? {
        val participants = getConversationDetail(viewer, conversationId).conversation.participants
        return participants.singleOrNull { it.userId == user.toString() }?.status
    }

    private suspend fun refusalCode(block: suspend () -> Unit): String = assertFailsWith<MessagingException> { block() }.code

    // --- 1. An acceptance never carries over to a group that changed ---

    @Test
    fun anAcceptanceIsResetWhenSomeoneJoinsAndTheInviteeMustAcceptAgain() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.acceptPartnerInvite(jo, conversationId) // Jo says yes to Alex and Sam.
            service.inviteLinkedPartnerToConversation(sam, conversationId)
            service.acceptPartnerInvite(alex, conversationId, service.inviteIdFor(riley, conversationId))
            service.acceptPartnerInvite(riley, conversationId) // Riley joins.

            // Jo's yes was for a group without Riley: back to pending, and Sam's approval does not add Jo.
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(jo, conversationId)?.partnerStatus)
            service.acceptPartnerInvite(sam, conversationId, service.inviteIdFor(jo, conversationId))
            assertEquals(ConversationParticipantStatus.INVITED, service.statusOf(jo, conversationId))
            // Jo is asked again and now sees Riley among the people in the chat.
            val joView = service.getConversationDetail(jo, conversationId).conversation
            assertEquals(
                setOf(alex, sam, riley, jo).map { it.toString() }.toSet(),
                joView.participants.map { it.userId }.toSet(),
            )
            service.acceptPartnerInvite(jo, conversationId)
            assertEquals(ConversationParticipantStatus.ACTIVE, service.statusOf(jo, conversationId))
        }

    @Test
    fun anInviteeWhoseGroupChangedCanDeclineInstead() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.acceptPartnerInvite(jo, conversationId)
            service.inviteLinkedPartnerToConversation(sam, conversationId)
            service.acceptPartnerInvite(alex, conversationId, service.inviteIdFor(riley, conversationId))
            service.acceptPartnerInvite(riley, conversationId)

            service.declinePartnerInvite(jo, conversationId)

            assertNull(service.statusOf(jo, conversationId))
            assertNull(service.inviteFor(jo, conversationId))
        }

    @Test
    fun anAcceptanceIsResetWhenSomeoneLeaves() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.acceptPartnerInvite(jo, conversationId)

            service.leaveConversation(sam, conversationId)

            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(jo, conversationId)?.partnerStatus)
        }

    @Test
    fun aBlockCreatedWhileAnInviteeWaitsStopsTheirActivation() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.inviteLinkedPartnerToConversation(sam, conversationId)
            service.acceptPartnerInvite(jo, conversationId)
            // Riley (another open invitee) blocks Jo while Jo waits for the chat's approval.
            dataSource.insertBlock(blockerUserId = riley, blockedUserId = jo)

            val code = refusalCode { service.acceptPartnerInvite(sam, conversationId, service.inviteIdFor(jo, conversationId)) }

            assertEquals("MESSAGING_NOT_ALLOWED", code)
            assertEquals(ConversationParticipantStatus.INVITED, service.statusOf(jo, conversationId))
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(jo, conversationId)?.thirdPartyStatus)
        }

    // --- 2. An id-less accept never approves the wrong invite ---

    @Test
    fun aRetriedAcceptAfterALostResponseNeverApprovesAnotherInvite() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.inviteLinkedPartnerToConversation(sam, conversationId)
            service.acceptPartnerInvite(sam, conversationId, service.inviteIdFor(jo, conversationId))
            service.acceptPartnerInvite(jo, conversationId) // Jo joins; say the response is lost.

            // The retry, with no invite id, is an idempotent success for Jo, not an approval of Riley.
            val retried = service.acceptPartnerInvite(jo, conversationId)
            assertEquals(
                ConversationParticipantStatus.ACTIVE,
                retried.conversation.participants
                    .single { it.userId == jo.toString() }
                    .status,
            )
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(riley, conversationId)?.thirdPartyStatus)
        }

    @Test
    fun aRetryAfterAnotherInviteWasCreatedLaterStillNeverApprovesIt() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.acceptPartnerInvite(sam, conversationId)
            service.acceptPartnerInvite(jo, conversationId) // Jo joins; say the response is lost.
            service.inviteLinkedPartnerToConversation(sam, conversationId) // Riley is invited afterwards.

            service.acceptPartnerInvite(jo, conversationId) // The retry, with no invite id.

            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(riley, conversationId)?.thirdPartyStatus)
            // Jo approves Riley only by naming the invite.
            service.acceptPartnerInvite(jo, conversationId, service.inviteIdFor(riley, conversationId))
            assertEquals(PartnerChatInviteStatus.ACCEPTED, service.inviteFor(riley, conversationId)?.thirdPartyStatus)
        }

    @Test
    fun anIdLessApprovalWithTwoApprovableInvitesIsRefused() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.acceptPartnerInvite(sam, conversationId)
            service.acceptPartnerInvite(jo, conversationId) // Jo is in.
            // Alex and Jo later split and Jo links with Kim; Jo invites Kim and Sam invites Riley.
            sql("UPDATE user_profile_links SET status = 'unlinked', unlinked_at = now() WHERE user_id IN ('$alex', '$jo')")
            link(jo, kim)
            service.inviteLinkedPartnerToConversation(jo, conversationId)
            service.inviteLinkedPartnerToConversation(sam, conversationId)

            // Alex could approve either: without an id nothing is chosen for them.
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.acceptPartnerInvite(alex, conversationId) })
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.declinePartnerInvite(alex, conversationId) })
            // Naming one works and touches only that one.
            service.acceptPartnerInvite(alex, conversationId, service.inviteIdFor(kim, conversationId))
            assertEquals(PartnerChatInviteStatus.ACCEPTED, service.inviteFor(kim, conversationId)?.thirdPartyStatus)
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(riley, conversationId)?.thirdPartyStatus)
        }

    @Test
    fun anInviteIdFromAnotherConversationIsRefused() =
        runBlocking {
            val service = service()
            val first = service.conversation()
            val other = service.conversation(first = alex, second = kim)
            service.inviteLinkedPartnerToConversation(sam, first)
            val rileyInvite = service.inviteIdFor(riley, first)
            service.inviteLinkedPartnerToConversation(alex, other)

            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.acceptPartnerInvite(kim, other, rileyInvite) })
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.declinePartnerInvite(kim, other, rileyInvite) })
            assertEquals(PartnerChatInviteStatus.PENDING, service.inviteFor(riley, first)?.thirdPartyStatus)
        }

    // --- 3. What an invited person can see ---

    @Test
    fun anInvitedPersonSeesOnlyWhoIsInAndTheirOwnInvite() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            sql("UPDATE discovery_settings SET partner_visibility = 'hidden' WHERE user_id = '$sam'")
            service.inviteLinkedPartnerToConversation(sam, conversationId)
            service.inviteLinkedPartnerToConversation(alex, conversationId)

            val views =
                listOf(
                    service.getConversationDetail(jo, conversationId).conversation,
                    service.markConversationRead(jo, conversationId),
                    service.listConversations(jo).items.single { it.conversationId == conversationId },
                )
            views.forEach { view ->
                assertEquals(setOf(alex, sam, jo).map { it.toString() }.toSet(), view.participants.map { it.userId }.toSet())
                assertTrue(view.participants.filter { it.userId != jo.toString() }.all { it.invitedByUserId == null })
                assertEquals(alex.toString(), view.participants.single { it.userId == jo.toString() }.invitedByUserId)
                assertEquals(listOf(jo.toString()), view.partnerInvites.map { it.invitedPartnerUserId })
                assertEquals(jo.toString(), view.partnerInvite?.invitedPartnerUserId)
            }
            // Active people see everyone, including who invited whom (inviting reveals the link to the chat).
            val alexView = service.getConversationDetail(alex, conversationId).conversation
            assertEquals(sam.toString(), alexView.participants.single { it.userId == riley.toString() }.invitedByUserId)
        }

    @Test
    fun aThreadWithThreeActivePeopleLetsEveryNonInviterApprove() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.acceptPartnerInvite(sam, conversationId)
            service.acceptPartnerInvite(jo, conversationId) // Alex, Sam and Jo are in.
            service.inviteLinkedPartnerToConversation(sam, conversationId)
            val rileyInvite = service.inviteIdFor(riley, conversationId)

            // Riley sees the three active people and nothing more.
            val rileyView = service.getConversationDetail(riley, conversationId).conversation
            assertEquals(4, rileyView.participants.size)
            // Sam cannot approve their own invite; Jo (a non-inviter, active) can.
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.acceptPartnerInvite(sam, conversationId, rileyInvite) })
            service.acceptPartnerInvite(jo, conversationId, rileyInvite)
            assertEquals(PartnerChatInviteStatus.ACCEPTED, service.inviteFor(riley, conversationId)?.thirdPartyStatus)
        }

    // --- 4. Cooldown after a decline by either side ---

    @Test
    fun theInviteesOwnDeclineStartsTheCooldown() =
        runBlocking {
            val service = service()
            val conversationId = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.declinePartnerInvite(jo, conversationId)

            assertEquals("PARTNER_INVITE_COOLDOWN", refusalCode { service.inviteLinkedPartnerToConversation(alex, conversationId) })
            assertEquals(true, service.markConversationRead(alex, conversationId).ownPartnerInviteCoolingDown)
            assertEquals(false, service.markConversationRead(sam, conversationId).ownPartnerInviteCoolingDown)
        }

    // --- Refusals for people who are not (or no longer) party ---

    @Test
    fun peopleWhoLeftDeclinedOrAreBlockedCannotAnswer() =
        runBlocking {
            val service = service()
            // Left: Sam leaves, then tries to approve or decline Jo's invite.
            val left = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, left)
            val joInvite = service.inviteIdFor(jo, left)
            service.leaveConversation(sam, left)
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.acceptPartnerInvite(sam, left, joInvite) })
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.declinePartnerInvite(sam, left, joInvite) })

            // Declined: Jo declined, then tries to accept or decline again.
            reset()
            val declined = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, declined)
            service.declinePartnerInvite(jo, declined)
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.acceptPartnerInvite(jo, declined) })
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.declinePartnerInvite(jo, declined) })

            // Blocked: Sam blocked Alex after Alex invited Jo; Sam may not approve or decline that invite,
            // so Jo, who may still say yes, cannot be activated.
            reset()
            val blocked = service.conversation()
            service.inviteLinkedPartnerToConversation(alex, blocked)
            val blockedInvite = service.inviteIdFor(jo, blocked)
            dataSource.insertBlock(blockerUserId = sam, blockedUserId = alex)
            assertEquals("MESSAGING_NOT_ALLOWED", refusalCode { service.acceptPartnerInvite(sam, blocked, blockedInvite) })
            assertEquals("PARTNER_INVITE_NOT_FOUND", refusalCode { service.declinePartnerInvite(sam, blocked, blockedInvite) })
            service.acceptPartnerInvite(jo, blocked)
            assertEquals(ConversationParticipantStatus.INVITED, service.statusOf(jo, blocked, viewer = jo))
        }

    // --- Concurrency ---

    @Test
    fun anAcceptAndADeclineOfOneInviteAtOnceEndInOneConsistentOutcome() =
        runBlocking {
            val service = service()
            var wrong = 0
            repeat(ITERATIONS) {
                reset()
                val conversationId = service.conversation()
                service.inviteLinkedPartnerToConversation(alex, conversationId)
                service.acceptPartnerInvite(sam, conversationId)
                raceTogether(
                    { service.acceptPartnerInvite(jo, conversationId) },
                    { service.declinePartnerInvite(jo, conversationId) },
                )
                val status = service.statusOf(jo, conversationId)
                val inviteOpen = service.inviteFor(jo, conversationId) != null
                // Either Jo joined (accept won) or Jo is out (decline won); never half of each.
                val consistent =
                    (status == ConversationParticipantStatus.ACTIVE && !inviteOpen) ||
                        (status == null && !inviteOpen)
                if (!consistent) wrong++
            }
            assertEquals(0, wrong, "iterations out of $ITERATIONS that ended inconsistent")
        }

    @Test
    fun twoAcceptsOfOneInviteAtOnceActivateOnce() =
        runBlocking {
            val service = service()
            var wrong = 0
            repeat(ITERATIONS) {
                reset()
                val conversationId = service.conversation()
                service.inviteLinkedPartnerToConversation(alex, conversationId)
                service.acceptPartnerInvite(jo, conversationId)
                val joInvite = service.inviteIdFor(jo, conversationId)
                raceTogether(
                    { service.acceptPartnerInvite(sam, conversationId, joInvite) },
                    { service.acceptPartnerInvite(sam, conversationId, joInvite) },
                )
                if (service.statusOf(jo, conversationId) != ConversationParticipantStatus.ACTIVE) wrong++
                if (service.inviteFor(jo, conversationId) != null) wrong++
            }
            assertEquals(0, wrong, "iterations out of $ITERATIONS that did not end with Jo active once")
        }

    @Test
    fun twoInvitesResolvedAtOnceNeverActivateSomeoneWithAStaleYes() =
        runBlocking {
            val service = service()
            var wrong = 0
            repeat(ITERATIONS) {
                reset()
                val conversationId = service.conversation()
                service.inviteLinkedPartnerToConversation(alex, conversationId)
                service.inviteLinkedPartnerToConversation(sam, conversationId)
                service.acceptPartnerInvite(jo, conversationId)
                service.acceptPartnerInvite(riley, conversationId)
                val joInvite = service.inviteIdFor(jo, conversationId)
                val rileyInvite = service.inviteIdFor(riley, conversationId)
                raceTogether(
                    { service.acceptPartnerInvite(sam, conversationId, joInvite) },
                    { service.acceptPartnerInvite(alex, conversationId, rileyInvite) },
                )
                // Whichever joined first, the other's earlier yes was reset: exactly one is in.
                val active =
                    listOf(jo, riley).count { service.statusOf(it, conversationId) == ConversationParticipantStatus.ACTIVE }
                if (active != 1) wrong++
            }
            assertEquals(0, wrong, "iterations out of $ITERATIONS without exactly one partner in")
        }

    private companion object {
        const val ITERATIONS = 20
    }
}
