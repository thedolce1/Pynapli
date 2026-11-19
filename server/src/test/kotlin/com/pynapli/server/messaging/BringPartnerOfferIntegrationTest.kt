package com.pynapli.server.messaging

import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertBlock
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.insertHiddenProfile
import com.pynapli.server.grid.insertMatch
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerOffersDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Bring your partner" (MESSAGING_SPEC "Partner Invite Flow"): a server-decided, read-only offer on a
 * matched conversation. Using it is the ordinary partner invite, with the same accepts.
 * Alex is linked with Jo; Sam is linked with Riley; Alex and Sam match.
 */
class BringPartnerOfferIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private val alex = UUID.fromString("00000000-0000-0000-0000-000000000c01")
    private val jo = UUID.fromString("00000000-0000-0000-0000-000000000c02")
    private val sam = UUID.fromString("00000000-0000-0000-0000-000000000c03")
    private val riley = UUID.fromString("00000000-0000-0000-0000-000000000c04")

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        dataSource.insertDiscoverableUser(alex, "Alex", 35, -79.38, 43.65)
        dataSource.insertDiscoverableUser(jo, "Jo", 34, -79.381, 43.651)
        dataSource.insertDiscoverableUser(sam, "Sam", 33, -79.382, 43.652)
        dataSource.insertDiscoverableUser(riley, "Riley", 32, -79.383, 43.653)
        link(alex, jo)
        link(sam, riley)
    }

    private fun service(): MessagingService =
        MessagingService(
            JdbcMessagingRepository(dataSource),
            blockRepository = JdbcBlockRepository(dataSource),
        )

    private fun link(
        userA: UUID,
        userB: UUID,
    ) {
        dataSource.insertActiveProfileLink(userId = userA, linkedUserId = userB)
        dataSource.insertActiveProfileLink(userId = userB, linkedUserId = userA)
    }

    private suspend fun MessagingService.matchConversation(
        userA: UUID,
        userB: UUID,
    ): String {
        dataSource.insertMatch(userA, userB)
        openMatchConversation(userA, userB)
        return listConversations(userA).items.single { conversation ->
            conversation.participants.any { it.userId == userB.toString() }
        }.conversationId
    }

    private suspend fun MessagingService.offers(
        userId: UUID,
        conversationId: String,
    ): ConversationPartnerOffersDto? = getConversationDetail(userId, conversationId).conversation.partnerOffers

    private fun sql(statementText: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(statementText) }
        }
    }

    @Test
    fun aMatchOffersEachPersonTheirOwnPartnerAndNamesTheOtherVisiblePartner() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)

            val alexOffers = assertNotNull(service.offers(alex, conversationId))
            assertEquals(jo.toString(), alexOffers.bringPartner?.partnerUserId)
            assertEquals("Jo", alexOffers.bringPartner?.partnerDisplayName)
            assertEquals(sam.toString(), alexOffers.participantPartners.single().participantUserId)
            assertEquals("Riley", alexOffers.participantPartners.single().partnerDisplayName)

            val samOffers = assertNotNull(service.offers(sam, conversationId))
            assertEquals(riley.toString(), samOffers.bringPartner?.partnerUserId)
            assertEquals("Jo", samOffers.participantPartners.single().partnerDisplayName)

            // The read mark the client applies after opening carries the same offer.
            assertEquals(
                jo.toString(),
                service.markConversationRead(alex, conversationId).partnerOffers?.bringPartner?.partnerUserId,
            )
            // The inbox list does not carry it.
            assertNull(service.listConversations(alex).items.single().partnerOffers)
        }

    @Test
    fun aConversationWithoutAMatchOffersNothing() =
        runBlocking {
            val service = service()
            val conversationId =
                service
                    .createDirectConversation(
                        alex,
                        CreateDirectConversationRequestDto(targetUserId = sam.toString(), origin = "grid"),
                    ).conversation.conversationId

            assertNull(service.offers(alex, conversationId))
            assertNull(service.offers(sam, conversationId))
        }

    @Test
    fun usingTheOfferCreatesOnlyTheManualInviteAndEveryoneStillAccepts() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)

            val invited = service.inviteLinkedPartnerToConversation(alex, conversationId)

            // Exactly the manual invite: Jo invited, nothing accepted, no one else touched.
            val invite = assertNotNull(invited.partnerInvite)
            assertEquals(jo.toString(), invite.invitedPartnerUserId)
            assertEquals(PartnerChatInviteStatus.PENDING, invite.partnerStatus)
            assertEquals(PartnerChatInviteStatus.PENDING, invite.thirdPartyStatus)
            assertEquals(
                ConversationParticipantStatus.INVITED,
                invited.participants.single { it.userId == jo.toString() }.status,
            )
            assertNull(invited.participants.firstOrNull { it.userId == riley.toString() })
            // One invite at a time: no offer for anyone while it is open.
            assertNull(service.offers(alex, conversationId))
            assertNull(service.offers(sam, conversationId))

            // The same two accepts as today.
            service.acceptPartnerInvite(sam, conversationId)
            val joined = service.acceptPartnerInvite(jo, conversationId)
            assertEquals(
                ConversationParticipantStatus.ACTIVE,
                joined.conversation.participants.single { it.userId == jo.toString() }.status,
            )

            // Jo's invite exists: Alex is not offered Jo again; Sam can now bring Riley.
            assertNull(service.offers(alex, conversationId)?.bringPartner)
            assertEquals(riley.toString(), service.offers(sam, conversationId)?.bringPartner?.partnerUserId)

            // Riley declines: quiet and final, the offer never returns.
            service.inviteLinkedPartnerToConversation(sam, conversationId)
            service.declinePartnerInvite(riley, conversationId)
            assertNull(service.offers(sam, conversationId))
            assertNull(service.offers(alex, conversationId))
            assertNull(service.offers(jo, conversationId))
        }

    @Test
    fun aDeclinedOwnInviteIsNotOfferedAgain() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.declinePartnerInvite(sam, conversationId)

            // Sam's own offer for Riley stays; Alex's for Jo is gone for good.
            assertNull(service.offers(alex, conversationId)?.bringPartner)
            assertEquals(riley.toString(), service.offers(sam, conversationId)?.bringPartner?.partnerUserId)
        }

    @Test
    fun aHiddenPartnerIsNeverNamed() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET partner_visibility = 'hidden' WHERE user_id = '$sam'")

            val alexOffers = service.offers(alex, conversationId)
            assertEquals(jo.toString(), alexOffers?.bringPartner?.partnerUserId)
            assertTrue(alexOffers?.participantPartners.orEmpty().isEmpty())
            // Sam still sees their own partner.
            assertEquals(riley.toString(), service.offers(sam, conversationId)?.bringPartner?.partnerUserId)
        }

    @Test
    fun aVerifiedOnlyPartnerIsNamedOnlyToAVerifiedViewer() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET partner_visibility = 'verified_only' WHERE user_id = '$sam'")
            assertTrue(service.offers(alex, conversationId)?.participantPartners.orEmpty().isEmpty())

            sql("UPDATE profiles SET verified_at = now() WHERE user_id = '$alex'")
            assertEquals("Riley", service.offers(alex, conversationId)?.participantPartners?.single()?.partnerDisplayName)
        }

    @Test
    fun aMatchesOnlyPartnerIsNamedToTheMatch() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET partner_visibility = 'matches_only' WHERE user_id = '$sam'")

            assertEquals("Riley", service.offers(alex, conversationId)?.participantPartners?.single()?.partnerDisplayName)
        }

    @Test
    fun anIncognitoPartnerIsNeverNamed() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET incognito = true WHERE user_id = '$riley'")

            assertTrue(service.offers(alex, conversationId)?.participantPartners.orEmpty().isEmpty())
        }

    @Test
    fun aPartnerTheViewerHidIsNeverNamed() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            dataSource.insertHiddenProfile(hiderUserId = alex, hiddenUserId = riley)

            assertTrue(service.offers(alex, conversationId)?.participantPartners.orEmpty().isEmpty())
        }

    @Test
    fun aBlockBetweenAnyTwoWouldBeParticipantsStopsTheOfferAndTheInvite() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            // Jo blocked Sam: Alex is not offered Jo, and the invite itself is refused generically.
            dataSource.insertBlock(blockerUserId = jo, blockedUserId = sam)

            assertNull(service.offers(alex, conversationId))
            assertNull(service.offers(sam, conversationId))
            val refusal =
                assertFailsWith<MessagingException> {
                    service.inviteLinkedPartnerToConversation(alex, conversationId)
                }
            assertEquals("MESSAGING_NOT_ALLOWED", refusal.code)
        }

    @Test
    fun aBlockBetweenTheTwoPartnersStopsEveryOffer() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            dataSource.insertBlock(blockerUserId = riley, blockedUserId = jo)

            // All four would end up together: nobody is offered or named, and nothing says who blocked.
            assertNull(service.offers(alex, conversationId))
            assertNull(service.offers(sam, conversationId))
        }

    @Test
    fun aBlockInvolvingAPartnerHiddenFromTheViewerChangesNothingForThem() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET partner_visibility = 'hidden' WHERE user_id = '$sam'")
            dataSource.insertBlock(blockerUserId = riley, blockedUserId = jo)

            // Alex cannot see Riley, so Riley's block must not reveal itself by hiding Alex's offer.
            val alexOffers = service.offers(alex, conversationId)
            assertEquals(jo.toString(), alexOffers?.bringPartner?.partnerUserId)
            assertTrue(alexOffers?.participantPartners.orEmpty().isEmpty())
            // Sam sees both partners (their own, and Jo is public), so Sam's offer stops.
            assertNull(service.offers(sam, conversationId))
        }

    @Test
    fun aPsyncTogetherMatchGetsOneGroupNotTwo() =
        runBlocking {
            val service = service()
            // A Psync together match: Alex and Jo each match Sam and get their own conversation with Sam.
            val alexSam = service.matchConversation(alex, sam)
            val joSam = service.matchConversation(jo, sam)
            assertEquals(jo.toString(), service.offers(alex, alexSam)?.bringPartner?.partnerUserId)
            assertEquals(alex.toString(), service.offers(jo, joSam)?.bringPartner?.partnerUserId)

            service.inviteLinkedPartnerToConversation(alex, alexSam)

            // Jo's own conversation with Sam stops offering anything, to Jo or to Sam.
            assertNull(service.offers(jo, joSam))
            assertNull(service.offers(sam, joSam))

            service.acceptPartnerInvite(sam, alexSam)
            service.acceptPartnerInvite(jo, alexSam)

            // The remaining step, Sam's partner, is offered in the group only.
            assertEquals(riley.toString(), service.offers(sam, alexSam)?.bringPartner?.partnerUserId)
            assertNull(service.offers(sam, joSam))
            assertNull(service.offers(jo, joSam))
        }
}
