package com.pynapli.server.metinperson

import com.pynapli.server.account.JdbcAccountDataExportArchiver
import com.pynapli.server.contactblocks.ContactBlockTestGraph
import com.pynapli.server.contactblocks.ContactBlockTokens
import com.pynapli.server.contactblocks.JdbcContactBlockVisibility
import com.pynapli.server.messaging.MessagingException
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.ConfirmMetInPersonRequestDto
import com.pynapli.shared.network.dto.WithdrawMetInPersonRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Met in person" under "Hide from people you know" (viewer side, TRUST_AND_SAFETY_SPEC): someone hiding
 * from the viewer is never offered, confirmable or exported to them, and a conversation that holds only
 * them answers exactly like a conversation that doesn't exist. Rows are written the way the resolver
 * leaves them (bound: `target_token` = the viewer's target token; exempt: the viewer's exempt token),
 * since the resolver itself would usually settle a pair that already talked as exempt.
 */
class MetInPersonContactBlockIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val clock = MetInPersonTestClock(Instant.parse("2026-11-18T12:00:00Z"))
    private val tokens = MetInPersonTokens("met-in-person-contact-block-key-0123456789")
    private val contactTokens = ContactBlockTokens.localDevelopment()
    private val fx = MetInPersonFixtures(dataSource, clock)
    private val service = MetInPersonService(dataSource, tokens, JdbcContactBlockVisibility(dataSource, contactTokens), clock)
    private val batch = MetInPersonBadgeBatch(dataSource, tokens, clock, Random(7))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    /** [blocker] hides from [viewer]: an active bound row. */
    private fun hide(
        blocker: UUID,
        viewer: UUID,
    ) = insertRow(blocker, contactTokens.targetToken(viewer))

    /** [blocker] added [viewer]'s address, but the pair was exempt (prior interaction). */
    private fun exempt(
        blocker: UUID,
        viewer: UUID,
    ) = insertRow(blocker, contactTokens.exemptToken(viewer))

    private fun insertRow(
        blocker: UUID,
        targetToken: String,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO contact_blocks (id, blocker_user_id, email_token, target_token) VALUES (gen_random_uuid(), ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, blocker)
                    statement.setString(2, "email-token-${UUID.randomUUID()}")
                    statement.setString(3, targetToken)
                    statement.executeUpdate()
                }
        }
    }

    private suspend fun refusalCode(block: suspend () -> Unit): String = assertFailsWith<MessagingException> { block() }.code

    @Test
    fun aConversationHoldingOnlySomeoneHidingAnswersLikeAnUnknownOne() {
        runBlocking {
            val viewer = fx.member("Xan")
            val hider = fx.member("Zephyrine Hidewell")
            val chat = fx.talked(viewer, hider)
            assertTrue(service.offers(viewer, chat.toString()).offers.any { it.subjectUserId == hider.toString() && it.canConfirm })

            hide(hider, viewer)

            val unknown = UUID.randomUUID().toString()
            val unknownOffers = refusalCode { service.offers(viewer, unknown) }
            val unknownConfirm = refusalCode { service.confirm(viewer, unknown, ConfirmMetInPersonRequestDto(hider.toString(), false)) }
            assertEquals(MetInPersonService.NOT_AVAILABLE, unknownOffers)
            assertEquals(unknownOffers, refusalCode { service.offers(viewer, chat.toString()) })
            assertEquals(
                unknownConfirm,
                refusalCode { service.confirm(viewer, chat.toString(), ConfirmMetInPersonRequestDto(hider.toString(), false)) },
            )
            // Nothing given: taking back is the same refusal as an unknown conversation.
            assertEquals(
                unknownConfirm,
                refusalCode { service.withdraw(viewer, chat.toString(), WithdrawMetInPersonRequestDto(hider.toString())) },
            )
            assertEquals(0, fx.vouchRows(), "nothing was written")
        }
    }

    @Test
    fun aConfirmationOfSomeoneNowHidingCanStillBeTakenBackWithoutEchoingTheirName() {
        runBlocking {
            val viewer = fx.member("Xan")
            val hider = fx.member("Zephyrine Hidewell")
            val chat = fx.talked(viewer, hider)
            assertTrue(service.confirm(viewer, chat.toString(), ConfirmMetInPersonRequestDto(hider.toString(), false)).given)

            hide(hider, viewer)

            val takenBack = service.withdraw(viewer, chat.toString(), WithdrawMetInPersonRequestDto(hider.toString()))
            assertFalse(takenBack.given)
            assertEquals("", takenBack.subjectDisplayName, "no name, hidden or not")
            assertEquals(1, fx.count("SELECT count(*) FROM vouches WHERE withdrawn_at IS NOT NULL"))
            assertEquals(
                MetInPersonService.NOT_AVAILABLE,
                refusalCode { service.withdraw(viewer, chat.toString(), WithdrawMetInPersonRequestDto(hider.toString())) },
                "nothing left to take back",
            )
        }
    }

    @Test
    fun anExemptPairIsUnaffected() {
        runBlocking {
            val viewer = fx.member("Xan")
            val subject = fx.member("Zephyrine Hidewell")
            val chat = fx.talked(viewer, subject)
            exempt(subject, viewer)

            val offer = assertNotNull(service.offers(viewer, chat.toString()).offers.firstOrNull { it.subjectUserId == subject.toString() })
            assertTrue(offer.canConfirm)
            val confirmed = service.confirm(viewer, chat.toString(), ConfirmMetInPersonRequestDto(subject.toString(), false))
            assertTrue(confirmed.given)
            assertEquals(1, fx.vouchRows())
        }
    }

    @Test
    fun aGroupLeavesOutOnlyThePersonHiding() {
        runBlocking {
            val viewer = fx.member("Xan")
            val hider = fx.member("Zephyrine Hidewell")
            val other = fx.member("Quillon Seenmore")
            val group = fx.conversation(viewer, hider, other)
            fx.message(group, viewer, Duration.ofDays(10))
            fx.message(group, hider, Duration.ofDays(3))
            fx.message(group, other, Duration.ofDays(2))
            assertEquals(
                setOf(hider.toString(), other.toString()),
                service
                    .offers(viewer, group.toString())
                    .offers
                    .map { it.subjectUserId }
                    .toSet(),
            )

            hide(hider, viewer)

            val offers = service.offers(viewer, group.toString()).offers
            assertEquals(listOf(other.toString()), offers.map { it.subjectUserId })
            assertFalse(offers.any { it.subjectDisplayName == "Zephyrine Hidewell" })
            assertEquals(
                MetInPersonService.NOT_AVAILABLE,
                refusalCode { service.confirm(viewer, group.toString(), ConfirmMetInPersonRequestDto(hider.toString(), false)) },
            )
            assertTrue(service.confirm(viewer, group.toString(), ConfirmMetInPersonRequestDto(other.toString(), false)).given)
        }
    }

    @Test
    fun theExportLeavesOutConfirmationsOfSomeoneNowHiding() {
        runBlocking {
            val viewer = fx.member("Xan")
            val hider = fx.member("Zephyrine Hidewell")
            val other = fx.member("Quillon Seenmore")
            service.confirm(viewer, fx.talked(viewer, hider).toString(), ConfirmMetInPersonRequestDto(hider.toString(), false))
            service.confirm(viewer, fx.talked(viewer, other).toString(), ConfirmMetInPersonRequestDto(other.toString(), false))
            val archiver = JdbcAccountDataExportArchiver(dataSource, clock, contactTokens, MetInPersonAccountData(tokens))

            val before = archiver.buildArchive(viewer).bytes.decodeToString()
            assertTrue("Zephyrine Hidewell" in before && "Quillon Seenmore" in before)

            hide(hider, viewer)

            val after = archiver.buildArchive(viewer).bytes.decodeToString()
            assertFalse("Zephyrine Hidewell" in after, "the export never names someone hiding from the person")
            assertTrue("Quillon Seenmore" in after)
        }
    }

    @Test
    fun aProfileWhosePartnerIsHiddenShowsNoCoupleBadge() {
        runBlocking {
            val sam = fx.member("Sam")
            val riley = fx.member("Riley")
            val stranger = fx.member("Stranger")
            val viewer = fx.member("Xan")
            fx.link(sam, riley)
            fx.optIn(sam)
            fx.optIn(riley)
            repeat(3) { index ->
                val voucher = fx.member("Group voucher $index")
                val group = fx.conversation(sam, riley, voucher)
                fx.message(group, voucher, Duration.ofDays(10))
                fx.message(group, sam, Duration.ofDays(9))
                fx.message(group, riley, Duration.ofDays(8))
                service.confirm(
                    voucher,
                    group.toString(),
                    ConfirmMetInPersonRequestDto(sam.toString(), includePartner = true),
                )
            }
            repeat(8) { batch.recomputeNow() }
            val profiles = ContactBlockTestGraph(dataSource).profiles
            assertTrue(profiles.getPublicProfile(stranger, sam).metInPersonAsCouple, "the couple badge is published")
            assertTrue(profiles.getPublicProfile(viewer, sam).metInPersonAsCouple)

            hide(riley, viewer)

            val seen = profiles.getPublicProfile(viewer, sam)
            assertEquals(null, seen.linkedPartner)
            assertFalse(seen.coupleVerified)
            assertFalse(seen.metInPersonAsCouple, "the couple badge would name the hidden partner")
            assertTrue(seen.metInPerson, "Sam's own badge is Sam's")
            assertTrue(profiles.getPublicProfile(stranger, sam).metInPersonAsCouple, "nobody else is affected")
        }
    }
}
