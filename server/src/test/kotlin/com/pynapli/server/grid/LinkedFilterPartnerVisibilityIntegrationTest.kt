package com.pynapli.server.grid

import com.pynapli.server.contactblocks.ContactBlockTestGraph
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "Linked" filter (linkedOnly) is a privacy rule, not just a filter: it must never tell a viewer
 * that someone has a partner the card hides from them. Property, for every candidate and on Grid and
 * Swipe: "matches linkedOnly=true" == "the card shows this viewer a partner mini", and
 * "matches linkedOnly=false" == "the card shows none". The oracle is the service's own unfiltered
 * answer (hydration gate plus the service stripping a partner the viewer hid or who hides from the
 * viewer), so the test follows the card, whatever the card rule becomes.
 *
 * Before LinkedPartnerFilterSql the rank predicates ignored the owner's partner_visibility, the
 * viewer's hidden list and contact blocks, so E1 (hidden), N1 (matches only, no match), VO1 (verified
 * only, unverified viewer), G1 (viewer hid G2) and CB1 (CB2 hides from the viewer) all matched
 * "Linked" while their cards showed no partner.
 */
class LinkedFilterPartnerVisibilityIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private lateinit var graph: ContactBlockTestGraph

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        graph = ContactBlockTestGraph(dataSource)
        nextOffset = 0
    }

    @Test
    fun gridLinkedMatchesExactlyTheCardsThatShowAPartner() =
        runBlocking {
            val world = seedWorld()
            assertGridProperty(world)
            // The hiding cases match "not linked" (their cards show no partner), never "Linked".
            world.hiddenPartnerOwners.forEach { owner ->
                assertFalse(owner in gridIds(world.viewer, linkedOnly = true), "$owner hides a partner from the viewer")
                assertTrue(owner in gridIds(world.viewer, linkedOnly = false), "$owner shows no partner")
            }
            assertTrue(world.a1 in gridIds(world.viewer, linkedOnly = true))
            assertTrue(world.m1 in gridIds(world.viewer, linkedOnly = true), "matches_only and matched")
            assertFalse(world.vo1 in gridIds(world.viewer, linkedOnly = true), "verified_only, viewer unverified")

            // Once the viewer is verified, VO1's partner shows, and so VO1 is Linked.
            dataSource.markProfileVerified(world.viewer)
            assertGridProperty(world)
            assertTrue(world.vo1 in gridIds(world.viewer, linkedOnly = true))
        }

    @Test
    fun swipeLinkedMatchesExactlyTheCardsThatShowAPartner() =
        runBlocking {
            val world = seedWorld()
            assertSwipeProperty(world)
            world.hiddenPartnerOwners.forEach { owner ->
                assertFalse(owner in swipeIds(world.viewer, linkedOnly = true), "$owner hides a partner from the viewer")
                assertTrue(owner in swipeIds(world.viewer, linkedOnly = false), "$owner shows no partner")
            }
            assertFalse(world.vo1 in swipeIds(world.viewer, linkedOnly = true))

            dataSource.markProfileVerified(world.viewer)
            assertSwipeProperty(world)
            assertTrue(world.vo1 in swipeIds(world.viewer, linkedOnly = true))
        }

    /**
     * FILTERS_SPEC "Who": Singles and Partnered follow the visible link, not the free-text
     * relationship_context. Every seeded profile says "single" in that text (the fixture default),
     * linked or not, and none says "partnered", so before the fold Singles returned everyone and
     * Partnered nobody.
     */
    @Test
    fun singlesAndPartneredFollowTheVisibleLinkNotTheFreeText() =
        runBlocking {
            val world = seedWorld()
            val all = graph.grid.queryGrid(world.viewer, gridRequest(linkedOnly = null)).items
            val showsPartner = all.filter { it.linkedPartner != null }.map { UUID.fromString(it.userId) }.toSet()
            val everyone = all.map { UUID.fromString(it.userId) }.toSet()

            suspend fun grid(
                contexts: List<String>,
                linkedOnly: Boolean? = null,
            ): Set<UUID> =
                graph.grid
                    .queryGrid(world.viewer, gridRequest(linkedOnly).copy(relationshipContexts = contexts))
                    .items
                    .map { UUID.fromString(it.userId) }
                    .toSet()

            assertEquals(everyone - showsPartner, grid(listOf("single")), "Singles = no partner shown")
            assertEquals(showsPartner, grid(listOf("partnered")), "Partnered = a partner shown")
            // Exclusive states, so both at once is the union: everyone.
            assertEquals(everyone, grid(listOf("single", "partnered")))
            assertEquals(everyone, grid(listOf("single"), linkedOnly = true))
            // Any other free-text value filters nothing.
            assertEquals(everyone, grid(listOf("married, open")))

            // Swipe takes the same tokens.
            val swipeSingles =
                graph.swipe
                    .queryDeck(world.viewer, swipeRequest(linkedOnly = null).copy(relationshipContexts = listOf("single")))
                    .items
                    .map { UUID.fromString(it.userId) }
                    .toSet()
            assertEquals(swipeIds(world.viewer, linkedOnly = false), swipeSingles)
        }

    private suspend fun assertGridProperty(world: World) {
        val all = graph.grid.queryGrid(world.viewer, gridRequest(linkedOnly = null)).items
        val showsPartner = all.filter { it.linkedPartner != null }.map { UUID.fromString(it.userId) }.toSet()
        val everyone = all.map { UUID.fromString(it.userId) }.toSet()
        assertTrue(showsPartner.isNotEmpty() && (everyone - showsPartner).isNotEmpty(), "the world has both kinds")
        assertEquals(showsPartner, gridIds(world.viewer, linkedOnly = true), "Linked == shows a partner mini")
        assertEquals(everyone - showsPartner, gridIds(world.viewer, linkedOnly = false), "not linked == shows none")
    }

    private suspend fun assertSwipeProperty(world: World) {
        val all = graph.swipe.queryDeck(world.viewer, swipeRequest(linkedOnly = null)).items
        val showsPartner = all.filter { it.linkedPartner != null }.map { UUID.fromString(it.userId) }.toSet()
        val everyone = all.map { UUID.fromString(it.userId) }.toSet()
        assertTrue(showsPartner.isNotEmpty() && (everyone - showsPartner).isNotEmpty(), "the world has both kinds")
        assertEquals(showsPartner, swipeIds(world.viewer, linkedOnly = true), "Linked == shows a partner mini")
        assertEquals(everyone - showsPartner, swipeIds(world.viewer, linkedOnly = false), "not linked == shows none")
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private class World(
        val viewer: UUID,
        val a1: UUID,
        val m1: UUID,
        val vo1: UUID,
        val hiddenPartnerOwners: List<UUID>,
    )

    private fun seedWorld(): World {
        val viewer = person("Viewer")
        // A: public couple.
        val (a1, _) = couple("A")
        // VO: VO1 shows their partner to verified members only (the viewer starts unverified).
        val (vo1, _) = couple("VO")
        setPartnerVisibility(vo1, "verified_only")
        // M: matches only, and the viewer matched M1.
        val (m1, _) = couple("M")
        setPartnerVisibility(m1, "matches_only")
        dataSource.insertMatch(viewer, m1)
        // N: matches only, no match.
        val (n1, _) = couple("N")
        setPartnerVisibility(n1, "matches_only")
        // E: E1 hides their partner from everyone (E2, public, still shows E1).
        val (e1, _) = couple("E")
        setPartnerVisibility(e1, "hidden")
        // G: the viewer hid G2.
        val (g1, g2) = couple("G")
        dataSource.insertHiddenProfile(viewer, g2)
        // CB: CB2 hides from the viewer ("Hide from people you know", an active bound row).
        val (cb1, cb2) = couple("CB")
        insertContactBlock(blocker = cb2, viewer = viewer)
        // H: the viewer blocked H2; HB: HB2 blocked the viewer.
        val (h1, h2) = couple("H")
        dataSource.insertBlock(viewer, h2)
        val (hb1, hb2) = couple("HB")
        dataSource.insertBlock(hb2, viewer)
        // I: I2 is incognito.
        val (i1, i2) = couple("I")
        runSql("UPDATE discovery_settings SET incognito = true WHERE user_id = ?", i2)
        // P: a pending link only.
        val p1 = person("P1")
        val p2 = person("P2")
        runSql("INSERT INTO user_profile_links (id, user_id, linked_user_id, status) VALUES (?, ?, ?, 'pending')", UUID.randomUUID(), p1, p2)
        // S: a single.
        person("S")
        return World(
            viewer = viewer,
            a1 = a1,
            m1 = m1,
            vo1 = vo1,
            hiddenPartnerOwners = listOf(n1, e1, g1, cb1, h1, hb1, i1, p1),
        )
    }

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

    // --- queries ------------------------------------------------------------------------------------

    // The maximum radius, so the Grid never widens and the whole world fits one page.
    private fun gridRequest(linkedOnly: Boolean?) = GridQueryRequestDto(radiusMeters = 100_000, limit = 120, linkedOnly = linkedOnly)

    // Linked and distance locked, so a thin deck never relaxes the filter away.
    private fun swipeRequest(linkedOnly: Boolean?) =
        SwipeDeckRequestDto(
            radiusMeters = 100_000,
            limit = 60,
            linkedOnly = linkedOnly,
            dealbreakers = setOf(SwipeDealbreakerKey.LINKED_ONLY, SwipeDealbreakerKey.DISTANCE),
        )

    private suspend fun gridIds(
        viewer: UUID,
        linkedOnly: Boolean?,
    ): Set<UUID> =
        graph.grid
            .queryGrid(viewer, gridRequest(linkedOnly))
            .items
            .map { UUID.fromString(it.userId) }
            .toSet()

    private suspend fun swipeIds(
        viewer: UUID,
        linkedOnly: Boolean?,
    ): Set<UUID> =
        graph.swipe
            .queryDeck(viewer, swipeRequest(linkedOnly))
            .items
            .map { UUID.fromString(it.userId) }
            .toSet()
}
