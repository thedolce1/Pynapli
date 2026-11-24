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
 * matched conversation, carried by the read mark every open ends with. Using it is the ordinary partner
 * invite, and both sides can use it at once. Alex is linked with Jo; Sam with Riley; Alex and Sam match.
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
            JdbcMessagingRepository(
                dataSource,
                contactBlockTokens =
                    com.pynapli.server.contactblocks.ContactBlockTokens
                        .localDevelopment(),
            ),
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
        return listConversations(userA)
            .items
            .single { conversation -> conversation.participants.any { it.userId == userB.toString() } }
            .conversationId
    }

    /** What the client sees: every open and refresh ends with the read mark, which carries the offer. */
    private suspend fun MessagingService.offers(
        userId: UUID,
        conversationId: String,
    ): ConversationPartnerOffersDto? = markConversationRead(userId, conversationId).partnerOffers

    private suspend fun MessagingService.inviteIdFor(
        partner: UUID,
        conversationId: String,
    ): String {
        val invites = getConversationDetail(alex, conversationId).conversation.partnerInvites
        return invites.single { it.invitedPartnerUserId == partner.toString() }.inviteId
    }

    /** Alex still has their own offer (Jo), and nobody else's partner is named. */
    private fun assertOnlyOwnOffer(offers: ConversationPartnerOffersDto?) {
        assertEquals(jo.toString(), offers?.bringPartner?.partnerUserId)
        assertTrue(offers?.participantPartners.orEmpty().isEmpty())
    }

    private suspend fun MessagingService.statusOf(
        user: UUID,
        conversationId: String,
    ): String? {
        val participants = getConversationDetail(alex, conversationId).conversation.participants
        return participants.singleOrNull { it.userId == user.toString() }?.status
    }

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

            // Only the read mark carries it: not the inbox list, not the plain detail read.
            assertNull(
                service
                    .listConversations(alex)
                    .items
                    .single()
                    .partnerOffers,
            )
            assertNull(service.getConversationDetail(alex, conversationId).conversation.partnerOffers)
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
    fun bothSidesCanBringTheirPartnerAtOnceAndEveryoneStillAccepts() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)

            val invited = service.inviteLinkedPartnerToConversation(alex, conversationId)

            // Exactly the manual invite: Jo invited, nothing accepted, no one else touched.
            val invite = assertNotNull(invited.partnerInvite)
            assertEquals(jo.toString(), invite.invitedPartnerUserId)
            assertEquals(PartnerChatInviteStatus.PENDING, invite.partnerStatus)
            assertEquals(PartnerChatInviteStatus.PENDING, invite.thirdPartyStatus)
            assertNull(invited.participants.firstOrNull { it.userId == riley.toString() })
            // Alex is not offered Jo again; Sam can bring Riley straight away, without waiting.
            assertNull(service.offers(alex, conversationId)?.bringPartner)
            assertEquals(riley.toString(), service.offers(sam, conversationId)?.bringPartner?.partnerUserId)

            service.inviteLinkedPartnerToConversation(sam, conversationId)
            assertEquals(
                2,
                service
                    .getConversationDetail(alex, conversationId)
                    .conversation.partnerInvites.size,
            )
            assertNull(service.offers(alex, conversationId))
            assertNull(service.offers(sam, conversationId))

            // Every individual accept, in any order; nothing joined until then.
            val joInvite = service.inviteIdFor(jo, conversationId)
            val rileyInvite = service.inviteIdFor(riley, conversationId)
            service.acceptPartnerInvite(jo, conversationId)
            service.acceptPartnerInvite(riley, conversationId)
            service.acceptPartnerInvite(sam, conversationId, joInvite)
            service.acceptPartnerInvite(alex, conversationId, rileyInvite)
            // Jo joined after Riley said yes: Riley must say yes again, knowing Jo is there.
            assertEquals(ConversationParticipantStatus.INVITED, service.statusOf(riley, conversationId))
            val all = service.acceptPartnerInvite(riley, conversationId)
            assertEquals(
                setOf(ConversationParticipantStatus.ACTIVE),
                all.conversation.participants
                    .map { it.status }
                    .toSet(),
            )
            assertEquals(4, all.conversation.participants.size)
            listOf(alex, sam, jo, riley).forEach { assertNull(service.offers(it, conversationId)) }
        }

    @Test
    fun aDeclineIsFinalForThatOfferAndLeavesTheOtherSideAlone() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.declinePartnerInvite(jo, conversationId)

            // Jo declined: Alex is never offered Jo here again; Sam's offer is unaffected.
            assertNull(service.offers(alex, conversationId)?.bringPartner)
            assertEquals(riley.toString(), service.offers(sam, conversationId)?.bringPartner?.partnerUserId)
            // Jo is no longer named to Sam either.
            assertTrue(
                service
                    .offers(sam, conversationId)
                    ?.participantPartners
                    .orEmpty()
                    .isEmpty(),
            )
            // And the manual button stays hidden for Alex during the cooldown (server-decided).
            assertEquals(true, service.markConversationRead(alex, conversationId).ownPartnerInviteCoolingDown)
            assertEquals(false, service.markConversationRead(sam, conversationId).ownPartnerInviteCoolingDown)
        }

    @Test
    fun aHiddenPartnerIsNeverNamed() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET partner_visibility = 'hidden' WHERE user_id = '$sam'")

            assertOnlyOwnOffer(service.offers(alex, conversationId))
            // Sam still sees their own partner.
            assertEquals(riley.toString(), service.offers(sam, conversationId)?.bringPartner?.partnerUserId)
        }

    @Test
    fun aVerifiedOnlyPartnerIsNamedOnlyToAVerifiedViewer() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET partner_visibility = 'verified_only' WHERE user_id = '$sam'")
            assertOnlyOwnOffer(service.offers(alex, conversationId))

            sql("UPDATE profiles SET verified_at = now() WHERE user_id = '$alex'")
            assertEquals(
                "Riley",
                service
                    .offers(alex, conversationId)
                    ?.participantPartners
                    ?.single()
                    ?.partnerDisplayName,
            )
        }

    @Test
    fun aMatchesOnlyPartnerIsNamedToTheMatch() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET partner_visibility = 'matches_only' WHERE user_id = '$sam'")

            assertEquals(
                "Riley",
                service
                    .offers(alex, conversationId)
                    ?.participantPartners
                    ?.single()
                    ?.partnerDisplayName,
            )
        }

    @Test
    fun anIncognitoPartnerIsNeverNamed() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE discovery_settings SET incognito = true WHERE user_id = '$riley'")

            assertOnlyOwnOffer(service.offers(alex, conversationId))
        }

    @Test
    fun aRiskRestrictedPartnerIsNeverNamed() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("INSERT INTO account_risk_states (user_id, score, tier) VALUES ('$riley', 90, 'quarantine')")

            assertOnlyOwnOffer(service.offers(alex, conversationId))
        }

    @Test
    fun aRiskRestrictedViewerIsNotOfferedTheirPartner() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("INSERT INTO account_risk_states (user_id, score, tier) VALUES ('$alex', 90, 'quarantine')")

            assertNull(service.offers(alex, conversationId)?.bringPartner)
        }

    @Test
    fun aPartnerWithAHiddenProfileIsNeverNamed() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            sql("UPDATE profiles SET visibility_status = 'hidden' WHERE user_id = '$riley'")

            assertOnlyOwnOffer(service.offers(alex, conversationId))
        }

    @Test
    fun aPartnerWhoBlockedTheViewerIsNotNamedAndDoesNotCostTheViewerTheirOffer() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            dataSource.insertBlock(blockerUserId = riley, blockedUserId = alex)

            // Like the Grid partner mini: Riley is simply not shown to Alex, so Alex's own offer stays.
            assertOnlyOwnOffer(service.offers(alex, conversationId))
            // Sam (Riley's partner) would put Riley and Alex together: no offer for Sam.
            assertNull(service.offers(sam, conversationId))
        }

    @Test
    fun theJoinedPartnerSeesTheOtherVisiblePartnerNoted() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            service.inviteLinkedPartnerToConversation(alex, conversationId)
            service.acceptPartnerInvite(sam, conversationId)
            service.acceptPartnerInvite(jo, conversationId)

            // Jo is active now: no offer of their own (Alex is here) and Riley is named by Sam's setting.
            val joOffers = assertNotNull(service.offers(jo, conversationId))
            assertNull(joOffers.bringPartner)
            assertEquals(sam.toString(), joOffers.participantPartners.single().participantUserId)
            assertEquals("Riley", joOffers.participantPartners.single().partnerDisplayName)
        }

    @Test
    fun aPartnerTheViewerHidIsNeverNamed() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            dataSource.insertHiddenProfile(hiderUserId = alex, hiddenUserId = riley)
            assertOnlyOwnOffer(service.offers(alex, conversationId))
        }

    @Test
    fun aBlockBetweenAnInviteeAndAParticipantStopsTheOfferAndTheInvite() =
        runBlocking {
            val service = service()
            val conversationId = service.matchConversation(alex, sam)
            dataSource.insertBlock(blockerUserId = jo, blockedUserId = sam)

            assertNull(service.offers(alex, conversationId))
            // Jo blocked Sam, so Jo is not shown to Sam at all (partner-mini rule): Sam keeps their own offer.
            val samOffers = service.offers(sam, conversationId)
            assertEquals(riley.toString(), samOffers?.bringPartner?.partnerUserId)
            assertTrue(samOffers?.participantPartners.orEmpty().isEmpty())
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
    fun aPsyncTogetherMatchOffersInOneConversationOnly() =
        runBlocking {
            val service = service()
            // A Psync together match: Alex and Jo each match Sam and get their own conversation with Sam.
            val alexSam = service.matchConversation(alex, sam)
            val joSam = service.matchConversation(jo, sam)
            sql("UPDATE conversations SET created_at = created_at - interval '1 second' WHERE id = '$alexSam'")
            recordTogetherMatch(initiator = alex, partner = jo, target = sam)

            // Offers live in the first conversation only, for everyone in it.
            assertEquals(jo.toString(), service.offers(alex, alexSam)?.bringPartner?.partnerUserId)
            assertEquals(riley.toString(), service.offers(sam, alexSam)?.bringPartner?.partnerUserId)
            assertNull(service.offers(jo, joSam))
            assertNull(service.offers(sam, joSam))

            // Both bring their partner at once; no second group, no duplicate invite.
            service.inviteLinkedPartnerToConversation(alex, alexSam)
            service.inviteLinkedPartnerToConversation(sam, alexSam)
            assertNull(service.offers(jo, joSam))
            assertNull(service.offers(sam, joSam))
            assertEquals(
                2,
                service
                    .getConversationDetail(sam, alexSam)
                    .conversation.partnerInvites.size,
            )
            assertTrue(
                service
                    .getConversationDetail(sam, joSam)
                    .conversation.partnerInvites
                    .isEmpty(),
            )
        }

    @Test
    fun thePsyncRuleFollowsTheRecordedMatchNotTheCouplesCurrentLink() =
        runBlocking {
            val service = service()
            val alexSam = service.matchConversation(alex, sam)
            val joSam = service.matchConversation(jo, sam)
            sql("UPDATE conversations SET created_at = created_at - interval '1 second' WHERE id = '$alexSam'")
            recordTogetherMatch(initiator = alex, partner = jo, target = sam)

            // The couple unlink: Sam's view of the second conversation must not change with that.
            sql("UPDATE user_profile_links SET status = 'unlinked', unlinked_at = now() WHERE user_id IN ('$alex', '$jo')")
            assertNull(service.offers(sam, joSam))

            // Alex and Sam unmatch: the first conversation no longer counts, so the second one offers.
            sql(
                """
                DELETE FROM matches
                WHERE LEAST(user_a, user_b) = LEAST('$alex'::uuid, '$sam'::uuid)
                  AND GREATEST(user_a, user_b) = GREATEST('$alex'::uuid, '$sam'::uuid)
                """.trimIndent(),
            )
            assertEquals(riley.toString(), service.offers(sam, joSam)?.bringPartner?.partnerUserId)
        }

    @Test
    fun aPartnersPrivateSoloMatchNeverSuppressesTheOtherPartnersOffer() =
        runBlocking {
            val service = service()
            // Alex matched Sam on their own, earlier; Jo matches Sam later. No together match.
            val alexSam = service.matchConversation(alex, sam)
            val joSam = service.matchConversation(jo, sam)
            sql("UPDATE conversations SET created_at = created_at - interval '1 second' WHERE id = '$alexSam'")

            // Jo's offer must not hint that Alex already matched Sam.
            assertEquals(alex.toString(), service.offers(jo, joSam)?.bringPartner?.partnerUserId)
            assertEquals(jo.toString(), service.offers(alex, alexSam)?.bringPartner?.partnerUserId)
        }

    /** The rows a Psync together match leaves (PsyncMatchResolution): a session of the pair, a together row. */
    private fun recordTogetherMatch(
        initiator: UUID,
        partner: UUID,
        target: UUID,
    ) {
        val sessionId = UUID.randomUUID()
        sql(
            """
            INSERT INTO psync_sessions (id, partner_link_id, initiator_user_id, status, deck_size, expires_at)
            SELECT '$sessionId', id, '$initiator', 'completed', 10, now() + interval '1 day'
            FROM user_profile_links WHERE user_id = '$initiator' AND linked_user_id = '$partner'
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO psync_session_participants (session_id, user_id, role, consent_status, approved)
            VALUES ('$sessionId', '$initiator', 'initiator', 'accepted', true),
                   ('$sessionId', '$partner', 'partner', 'accepted', true)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO psync_session_matches (id, session_id, target_user_id, kind, owner_user_id, match_id)
            VALUES ('${UUID.randomUUID()}', '$sessionId', '$target', 'together', NULL, NULL)
            """.trimIndent(),
        )
    }
}
