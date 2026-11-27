package com.pynapli.server.psync

import com.pynapli.server.contactblocks.ContactBlockTestGraph
import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.insertMatch
import com.pynapli.server.grid.markProfileVerified
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Psync deck is one deck shown to both partners of the session, so a candidate's partner is shown
 * (mini, couple badge) and counts for Linked / Verified couples only when BOTH partners may see them:
 * the strictest of the two views (PSYNC_SPEC). Before: the deck was hydrated and filtered from the
 * initiator's view alone, with no contact blocks, so partner B saw minis that matches_only /
 * verified_only granted only to A, and a Linked card whose partner contact-blocks B told B a hidden
 * partner existed.
 */
class PsyncPartnerVisibilityIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private lateinit var graph: ContactBlockTestGraph

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        graph = ContactBlockTestGraph(dataSource)
        nextOffset = 0
    }

    @Test
    fun aPartnerHidingFromEitherOfTheCoupleIsNeverShownOrLinked() =
        runBlocking {
            val (initiator, partner) = person("A") to person("B")
            val (x1, x2) = couple("X")
            // X2 hides from B only ("Hide from people you know").
            insertContactBlock(blocker = x2, viewer = partner)
            val (y1, y2) = couple("Y")
            // Y2 hides from A (the initiator) only.
            insertContactBlock(blocker = y2, viewer = initiator)
            val (p1, _) = couple("P")

            val linked = deckIds(initiator, partner, linkedOnly = true)
            assertFalse(x1 in linked, "X2 hides from B: X1 is not Linked in the couple's deck")
            assertFalse(y1 in linked, "Y2 hides from A: Y1 is not Linked in the couple's deck")
            assertTrue(p1 in linked)

            val all = deck(initiator, partner, linkedOnly = null)
            assertNull(all.card(x1).linkedPartner, "no X2 mini for either partner")
            assertNull(all.card(y1).linkedPartner, "no Y2 mini for either partner")
            assertNotNull(all.card(p1).linkedPartner)
            assertTrue(x1 in deckIds(initiator, partner, linkedOnly = false))

            // Sanity: on A's own Swipe deck (not Psync), X2 hides only from B, so X1 is Linked for A.
            val ownDeck =
                graph.swipe
                    .queryDeck(initiator, request(linkedOnly = true))
                    .items
                    .map { UUID.fromString(it.userId) }
            assertTrue(x1 in ownDeck)
            // X2 and Y2 themselves stay dealt (each partner's view leaves out who hides from them).
            assertTrue(x2 in all.map { UUID.fromString(it.userId) } && y2 in all.map { UUID.fromString(it.userId) })
        }

    @Test
    fun matchesOnlyGrantedToOnePartnerShowsTheMiniToNeitherInBothDirections() =
        runBlocking {
            val (a, b) = person("A") to person("B")
            // M1 matched A only; N1 matched B only. Both show their partner to matches only.
            val (m1, _) = couple("M")
            setPartnerVisibility(m1, "matches_only")
            dataSource.insertMatch(a, m1)
            val (n1, _) = couple("N")
            setPartnerVisibility(n1, "matches_only")
            dataSource.insertMatch(b, n1)
            // O1 matched both: both may see the partner.
            val (o1, _) = couple("O")
            setPartnerVisibility(o1, "matches_only")
            dataSource.insertMatch(a, o1)
            dataSource.insertMatch(b, o1)

            // Whoever starts the session, the result is the same.
            listOf(a to b, b to a).forEach { (initiator, partner) ->
                val all = deck(initiator, partner, linkedOnly = null)
                assertNull(all.card(m1).linkedPartner, "M1's partner is granted to A only")
                assertNull(all.card(n1).linkedPartner, "N1's partner is granted to B only")
                assertNotNull(all.card(o1).linkedPartner)
                val linked = deckIds(initiator, partner, linkedOnly = true)
                assertEquals(setOf(o1), linked.intersect(setOf(m1, n1, o1)))
            }
        }

    @Test
    fun verifiedOnlyNeedsBothPartnersVerifiedAndSoDoesTheCoupleBadge() =
        runBlocking {
            val (a, b) = person("A") to person("B")
            dataSource.markProfileVerified(a)
            // V: a verified couple showing the partner to verified members only.
            val (v1, v2) = couple("V")
            dataSource.markProfileVerified(v1)
            dataSource.markProfileVerified(v2)
            setPartnerVisibility(v1, "verified_only")
            // W: a verified, public couple.
            val (w1, w2) = couple("W")
            dataSource.markProfileVerified(w1)
            dataSource.markProfileVerified(w2)

            // A is verified, B is not: V1's partner is shown to neither, in either direction.
            listOf(a to b, b to a).forEach { (initiator, partner) ->
                val all = deck(initiator, partner, linkedOnly = null)
                assertNull(all.card(v1).linkedPartner)
                assertFalse(all.card(v1).coupleVerified)
                assertTrue(all.card(w1).coupleVerified)
                val couples = deckIds(initiator, partner, linkedOnly = null, verifiedCouplesOnly = true)
                assertFalse(v1 in couples)
                assertTrue(w1 in couples)
            }

            // Both verified: V1's partner shows to both.
            dataSource.markProfileVerified(b)
            assertNotNull(deck(a, b, linkedOnly = null).card(v1).linkedPartner)
            assertTrue(v1 in deckIds(a, b, linkedOnly = true))
        }

    /**
     * "Verified couples only" in Psync == the couple badge shows to BOTH partners. Before: the
     * couple-verified predicate had no contact-block exclusion, the Psync hydration dropped the hiding
     * partner in SQL (so the service backstop saw no partner id to drop on), and C1 was dealt with no
     * mini and no badge, revealing a hidden verified partner.
     */
    @Test
    fun aVerifiedPartnerHidingFromEitherOfTheCoupleKeepsTheCandidateOutOfVerifiedCouplesOnly() =
        runBlocking {
            val (a, b) = person("A") to person("B")
            // C2 hides from A; D2 hides from B; W is an ordinary verified couple.
            val (c1, c2) = verifiedCouple("C")
            insertContactBlock(blocker = c2, viewer = a)
            val (d1, d2) = verifiedCouple("D")
            insertContactBlock(blocker = d2, viewer = b)
            val (w1, w2) = verifiedCouple("W")

            listOf(a to b, b to a).forEach { (initiator, partner) ->
                val couples = deckIds(initiator, partner, linkedOnly = null, verifiedCouplesOnly = true)
                assertFalse(c1 in couples, "C2 hides from A")
                assertFalse(d1 in couples, "D2 hides from B")
                assertTrue(w1 in couples && w2 in couples)
                // Property: the filter keeps exactly the cards whose badge shows in the unfiltered deck.
                val badged = deck(initiator, partner, linkedOnly = null).filter { it.coupleVerified }.map { UUID.fromString(it.userId) }.toSet()
                assertEquals(badged, couples)
                // The hiding partners' own cards keep their badge: their partners hide from no one.
                assertTrue(c2 in couples && d2 in couples)
            }
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private var nextOffset = 0

    private fun person(name: String): UUID {
        val id = UUID.randomUUID()
        nextOffset += 1
        dataSource.insertDiscoverableUser(id, name, age = 35, longitude = -122.0841 + nextOffset * 0.0002, latitude = 37.4220)
        return id
    }

    private fun couple(name: String): Pair<UUID, UUID> {
        val first = person("${name}1")
        val second = person("${name}2")
        dataSource.insertActiveProfileLink(first, second)
        dataSource.insertActiveProfileLink(second, first)
        return first to second
    }

    private fun verifiedCouple(name: String): Pair<UUID, UUID> =
        couple(name).also { (first, second) ->
            dataSource.markProfileVerified(first)
            dataSource.markProfileVerified(second)
        }

    private fun setPartnerVisibility(
        owner: UUID,
        value: String,
    ) = runSql("UPDATE discovery_settings SET partner_visibility = ? WHERE user_id = ?", value, owner)

    private fun insertContactBlock(
        blocker: UUID,
        viewer: UUID,
    ) = runSql(
        "INSERT INTO contact_blocks (id, blocker_user_id, email_token, target_token) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        blocker,
        "email-token-${UUID.randomUUID()}",
        graph.tokens.targetToken(viewer),
    )

    private fun runSql(
        statement: String,
        vararg params: Any,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(statement).use { prepared ->
                params.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
                prepared.executeUpdate()
            }
        }
    }

    // Filters locked, so a thin deck never relaxes them away.
    private fun request(
        linkedOnly: Boolean?,
        verifiedCouplesOnly: Boolean = false,
    ) = SwipeDeckRequestDto(
        radiusMeters = 100_000,
        limit = 60,
        linkedOnly = linkedOnly,
        verifiedCouplesOnly = verifiedCouplesOnly,
        dealbreakers = setOf(SwipeDealbreakerKey.LINKED_ONLY, SwipeDealbreakerKey.DISTANCE, SwipeDealbreakerKey.VERIFIED),
    )

    /** The couple's deck exactly as the production Psync generator deals it. */
    private suspend fun deck(
        initiator: UUID,
        partner: UUID,
        linkedOnly: Boolean?,
        verifiedCouplesOnly: Boolean = false,
    ): List<SwipeCardDto> =
        SwipeServicePsyncDeckGenerator(graph.swipe)
            .rankCandidatesForCouple(initiator, partner, request(linkedOnly, verifiedCouplesOnly), limit = 60)

    private suspend fun deckIds(
        initiator: UUID,
        partner: UUID,
        linkedOnly: Boolean?,
        verifiedCouplesOnly: Boolean = false,
    ): Set<UUID> = deck(initiator, partner, linkedOnly, verifiedCouplesOnly).map { UUID.fromString(it.userId) }.toSet()

    private fun List<SwipeCardDto>.card(id: UUID): SwipeCardDto = single { it.userId == id.toString() }
}
