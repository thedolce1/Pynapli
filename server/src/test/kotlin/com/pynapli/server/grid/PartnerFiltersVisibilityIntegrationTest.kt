package com.pynapli.server.grid

import com.pynapli.server.contactblocks.ContactBlockTestGraph
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.PartnerFilterSql
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridCardDto
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan G2-3: partner filters are privacy rules as much as filters. Property, for every partner filter,
 * every visibility state and on Grid and Swipe: a candidate matches iff their card shows this viewer
 * a partner mini AND that partner's own declared value satisfies the filter. The oracle is the
 * service's own unfiltered answer (which partner the card shows), plus the partner attributes this
 * fixture wrote, so a hidden partner can never make a candidate match.
 *
 * Visibility states (the same world as LinkedFilterPartnerVisibilityIntegrationTest): public (A),
 * verified_only with an unverified then verified viewer (VO), matches_only matched (M) / not (N),
 * hidden (E1), viewer hid the partner (G), contact-blocked partner (CB), blocked either way (H, HB),
 * incognito partner (I), pending link (P).
 *
 * Every "first" member is a straight man of 45, unverified; every "second" member a bisexual woman of
 * 30, photo-verified. So each filter below has both matching and non-matching visible partners.
 */
class PartnerFiltersVisibilityIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource
    private lateinit var graph: ContactBlockTestGraph
    private val attributes = mutableMapOf<UUID, Attributes>()

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
        graph = ContactBlockTestGraph(dataSource)
        nextOffset = 0
        attributes.clear()
    }

    private val cases: List<Case> =
        listOf(
            Case("partner is a woman", GridQueryRequestDto(partnerGenders = listOf("woman"))) { it.gender == "woman" },
            Case("partner is a man", GridQueryRequestDto(partnerGenders = listOf("man"))) { it.gender == "man" },
            Case("partner aged 28-32", GridQueryRequestDto(partnerMinimumAge = 28, partnerMaximumAge = 32)) { it.age in 28..32 },
            Case("partner 40+", GridQueryRequestDto(partnerMinimumAge = 40)) { it.age >= 40 },
            Case("partner bisexual", GridQueryRequestDto(partnerOrientations = listOf("bisexual"))) { it.orientation == "bisexual" },
            Case("partner verified", GridQueryRequestDto(partnerVerifiedOnly = true)) { it.verified },
            Case(
                "all four at once",
                GridQueryRequestDto(
                    partnerGenders = listOf("woman"),
                    partnerMinimumAge = 18,
                    partnerMaximumAge = 35,
                    partnerOrientations = listOf("bisexual", "pansexual"),
                    partnerVerifiedOnly = true,
                ),
            ) { it.gender == "woman" && it.age <= 35 && it.orientation == "bisexual" && it.verified },
            Case("nobody's partner is non-binary", GridQueryRequestDto(partnerGenders = listOf("non_binary"))) { false },
        )

    @Test
    fun gridPartnerFiltersMatchOnlyThroughAPartnerTheCardShows() =
        runBlocking {
            val world = seedWorld()
            assertGridCases(world)
            // Once the viewer is verified, VO1's partner shows, so VO1 matches the partner filters.
            dataSource.markProfileVerified(world.viewer)
            assertGridCases(world)
            assertTrue(world.vo1 in gridIds(world.viewer, GridQueryRequestDto(partnerGenders = listOf("woman"))))
            // Partner filters imply Linked: with Singles ("no partner shown") nobody can match.
            assertTrue(gridIds(world.viewer, GridQueryRequestDto(linkedOnly = false, partnerGenders = listOf("woman"))).isEmpty())
            // With Linked they narrow it.
            assertEquals(
                gridIds(world.viewer, GridQueryRequestDto(partnerGenders = listOf("woman"))),
                gridIds(world.viewer, GridQueryRequestDto(linkedOnly = true, partnerGenders = listOf("woman"))),
            )
        }

    // Smoke only: proves the Visiting soon query binds the partner predicates (GridFilterSql is shared,
    // so the gate itself is the nearby one proven above). No trips are seeded, so nobody is listed.
    @Test
    fun visitingSoonBindsThePartnerPredicates() =
        runBlocking {
            val world = seedWorld()
            val visiting = gridIds(world.viewer, GridQueryRequestDto(visitingSoonOnly = true, partnerGenders = listOf("woman")))
            assertTrue(visiting.isEmpty())
        }

    @Test
    fun swipePartnerFiltersMatchOnlyThroughAPartnerTheCardShows() =
        runBlocking {
            val world = seedWorld()
            assertSwipeCases(world)
            dataSource.markProfileVerified(world.viewer)
            assertSwipeCases(world)
        }

    @Test
    fun noSingleMenDropsMenWithNoPartnerShownAndKeepsLinkedMen() =
        runBlocking {
            val world = seedWorld()
            val all = allGridCards(world.viewer)
            val expected =
                all
                    .filterNot { card -> attributes.getValue(card.id()).gender == "man" && card.linkedPartner == null }
                    .map { it.id() }
                    .toSet()
            val actual = gridIds(world.viewer, GridQueryRequestDto(excludeSingleMen = true))
            assertEquals(expected, actual)
            assertFalse(world.singleMan in actual, "a single man")
            assertTrue(world.singleWoman in actual, "a single woman stays")
            assertTrue(world.a1 in actual, "a man with a visible partner stays")
            // A man whose partner is hidden from the viewer looks single on his tile, and is treated so.
            world.hiddenPartnerOwners.forEach { owner -> assertFalse(owner in actual, "$owner shows no partner") }

            val swipe = swipeIds(world.viewer, SwipeDeckRequestDto(excludeSingleMen = true))
            val swipeAll = swipeCards(world.viewer, SwipeDeckRequestDto())
            val swipeExpected =
                swipeAll
                    .filterNot { attributes.getValue(UUID.fromString(it.userId)).gender == "man" && it.linkedPartner == null }
                    .map { UUID.fromString(it.userId) }
                    .toSet()
            assertEquals(swipeExpected, swipe)
        }

    @Test
    fun gridMiniCarriesThePartnersAgeAndGenderOnlyWhenItShows() =
        runBlocking {
            val world = seedWorld()
            val cards = allGridCards(world.viewer).associateBy { it.id() }
            val publicMini = assertNotNull(cards.getValue(world.a1).linkedPartner)
            assertEquals(30, publicMini.age)
            assertEquals("woman", publicMini.gender)
            world.hiddenPartnerOwners.forEach { owner ->
                cards[owner]?.let { card -> assertNull(card.linkedPartner, "$owner's partner is hidden from the viewer") }
            }
            // The public profile's partner preview fills them through the same gate.
            val profileMini = assertNotNull(graph.profiles.getPublicProfile(world.viewer, world.a1).linkedPartner)
            assertEquals(30, profileMini.age)
            assertEquals("woman", profileMini.gender)
            assertNull(graph.profiles.getPublicProfile(world.viewer, world.e1).linkedPartner)
            // G2-4 partner strip parity: every partner the tile strips, the preview strips too, including
            // the partner the viewer hid (G). Owners the viewer cannot open at all are skipped.
            world.hiddenPartnerOwners.forEach { owner ->
                runCatching { graph.profiles.getPublicProfile(world.viewer, owner) }
                    .getOrNull()
                    ?.let { profile -> assertNull(profile.linkedPartner, "$owner's partner is stripped from the preview") }
            }
        }

    /** Psync: the strictest of both session partners' views (PartnerFilterSql.matchingCandidateIds). */
    @Test
    fun psyncKeepsACandidateOnlyWhenBothSessionPartnersSeeAMatchingPartner() =
        runBlocking {
            val world = seedWorld()
            val verifiedViewer = person("Verified", gender = "woman", orientation = "bisexual", age = 30)
            dataSource.markProfileVerified(verifiedViewer)
            val filters = DiscoveryFilters(partnerGenders = setOf("woman"))
            val candidates = listOf(world.a1, world.vo1)

            fun matching(
                viewers: List<UUID>,
                hidden: Set<UUID> = emptySet(),
            ) = dataSource.connection.use { PartnerFilterSql.matchingCandidateIds(it, viewers, candidates, filters, hidden) }

            assertEquals(setOf(world.a1), matching(listOf(world.viewer)), "VO1 shows their partner to verified members only")
            assertEquals(setOf(world.a1, world.vo1), matching(listOf(verifiedViewer)))
            // Both partners as viewers: the gate is repeated per viewer, so the stricter view wins.
            assertEquals(setOf(world.a1), matching(listOf(world.viewer, verifiedViewer)))
            assertEquals(setOf(world.a1), matching(listOf(verifiedViewer, world.viewer)))
            // A partner hiding from one of them takes the candidate out.
            assertEquals(setOf(world.a1), matching(listOf(verifiedViewer), hidden = setOf(world.vo2)))
        }

    private suspend fun assertGridCases(world: World) {
        val all = allGridCards(world.viewer)
        cases.forEach { case ->
            val expected = all.filter { card -> card.partnerMatches(case.matches) }.map { it.id() }.toSet()
            assertEquals(expected, gridIds(world.viewer, case.request), "Grid: ${case.name}")
            world.hiddenPartnerOwners.forEach { owner ->
                assertFalse(owner in gridIds(world.viewer, case.request), "Grid ${case.name}: $owner's partner is hidden")
            }
        }
        assertTrue(cases.any { case -> all.any { it.partnerMatches(case.matches) } }, "the world has matches")
    }

    private suspend fun assertSwipeCases(world: World) {
        val all = swipeCards(world.viewer, SwipeDeckRequestDto())
        cases.forEach { case ->
            val expected =
                all
                    .filter { card -> card.linkedPartner?.let { case.matches(attributes.getValue(UUID.fromString(it.userId))) } == true }
                    .map { UUID.fromString(it.userId) }
                    .toSet()
            val request =
                SwipeDeckRequestDto(
                    partnerGenders = case.request.partnerGenders,
                    partnerMinimumAge = case.request.partnerMinimumAge,
                    partnerMaximumAge = case.request.partnerMaximumAge,
                    partnerOrientations = case.request.partnerOrientations,
                    partnerVerifiedOnly = case.request.partnerVerifiedOnly,
                )
            assertEquals(expected, swipeIds(world.viewer, request), "Swipe: ${case.name}")
        }
    }

    private fun GridCardDto.partnerMatches(predicate: (Attributes) -> Boolean): Boolean = linkedPartner?.let { predicate(attributes.getValue(UUID.fromString(it.userId))) } == true

    private fun GridCardDto.id(): UUID = UUID.fromString(userId)

    // --- fixtures ---------------------------------------------------------------------------------

    private class Case(
        val name: String,
        val request: GridQueryRequestDto,
        val matches: (Attributes) -> Boolean,
    )

    private data class Attributes(
        val gender: String,
        val orientation: String,
        val age: Int,
        val verified: Boolean,
    )

    private class World(
        val viewer: UUID,
        val a1: UUID,
        val e1: UUID,
        val vo1: UUID,
        val vo2: UUID,
        val singleMan: UUID,
        val singleWoman: UUID,
        val hiddenPartnerOwners: List<UUID>,
    )

    private fun seedWorld(): World {
        val viewer = person("Viewer", gender = "non_binary", orientation = "queer", age = 40)
        val (a1, _) = couple("A")
        val (vo1, vo2) = couple("VO")
        setPartnerVisibility(vo1, "verified_only")
        val (m1, _) = couple("M")
        setPartnerVisibility(m1, "matches_only")
        dataSource.insertMatch(viewer, m1)
        val (n1, _) = couple("N")
        setPartnerVisibility(n1, "matches_only")
        val (e1, _) = couple("E")
        setPartnerVisibility(e1, "hidden")
        val (g1, g2) = couple("G")
        dataSource.insertHiddenProfile(viewer, g2)
        val (cb1, cb2) = couple("CB")
        insertContactBlock(blocker = cb2, viewer = viewer)
        val (h1, h2) = couple("H")
        dataSource.insertBlock(viewer, h2)
        val (hb1, hb2) = couple("HB")
        dataSource.insertBlock(hb2, viewer)
        val (i1, i2) = couple("I")
        runSql("UPDATE discovery_settings SET incognito = true WHERE user_id = ?", i2)
        // Moderation and account states of the partner: quarantined, terminated, suspended, banned,
        // and a partner whose own profile is hidden. None of them is ever shown, so none counts.
        val (q1, q2) = couple("Q")
        dataSource.setRiskState(q2, score = 90, tier = "quarantine")
        val (t1, t2) = couple("T")
        dataSource.setRiskState(t2, score = 100, tier = "terminate")
        val (s1, s2) = couple("S")
        runSql("UPDATE users SET status = 'suspended' WHERE id = ?", s2)
        val (bn1, bn2) = couple("BN")
        runSql("UPDATE users SET status = 'banned' WHERE id = ?", bn2)
        val (ph1, ph2) = couple("PH")
        runSql("UPDATE profiles SET visibility_status = 'hidden' WHERE user_id = ?", ph2)
        val p1 = first("P1")
        val p2 = second("P2")
        runSql("INSERT INTO user_profile_links (id, user_id, linked_user_id, status) VALUES (?, ?, ?, 'pending')", UUID.randomUUID(), p1, p2)
        val singleMan = first("SM")
        val singleWoman = second("SW")
        return World(
            viewer = viewer,
            a1 = a1,
            e1 = e1,
            vo1 = vo1,
            vo2 = vo2,
            singleMan = singleMan,
            singleWoman = singleWoman,
            hiddenPartnerOwners = listOf(n1, e1, g1, cb1, h1, hb1, i1, q1, t1, s1, bn1, ph1, p1),
        )
    }

    private var nextOffset = 0

    private fun person(
        name: String,
        gender: String,
        orientation: String,
        age: Int,
        verified: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        nextOffset += 1
        dataSource.insertDiscoverableUser(id, name, age = age, longitude = -122.0841 + nextOffset * 0.0002, latitude = 37.4220)
        runSql("UPDATE profiles SET gender = ?, orientation = ?, display_age = ? WHERE user_id = ?", gender, orientation, age, id)
        if (verified) dataSource.markProfileVerified(id)
        attributes[id] = Attributes(gender, orientation, age, verified)
        return id
    }

    private fun first(name: String) = person(name, gender = "man", orientation = "straight", age = 45)

    private fun second(name: String) = person(name, gender = "woman", orientation = "bisexual", age = 30, verified = true)

    private fun couple(name: String): Pair<UUID, UUID> {
        val a = first("${name}1")
        val b = second("${name}2")
        dataSource.insertActiveProfileLink(a, b)
        dataSource.insertActiveProfileLink(b, a)
        return a to b
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
    private fun GridQueryRequestDto.wide() = copy(radiusMeters = 100_000, limit = 120)

    // Distance locked; partner filters never relax anyway.
    private fun SwipeDeckRequestDto.wide() = copy(radiusMeters = 100_000, limit = 60, dealbreakers = setOf(SwipeDealbreakerKey.DISTANCE))

    private suspend fun allGridCards(viewer: UUID): List<GridCardDto> = graph.grid.queryGrid(viewer, GridQueryRequestDto().wide()).items

    private suspend fun gridIds(
        viewer: UUID,
        request: GridQueryRequestDto,
    ): Set<UUID> =
        graph.grid
            .queryGrid(viewer, request.wide())
            .items
            .map { UUID.fromString(it.userId) }
            .toSet()

    private suspend fun swipeCards(
        viewer: UUID,
        request: SwipeDeckRequestDto,
    ) = graph.swipe.queryDeck(viewer, request.wide()).items

    private suspend fun swipeIds(
        viewer: UUID,
        request: SwipeDeckRequestDto,
    ): Set<UUID> = swipeCards(viewer, request).map { UUID.fromString(it.userId) }.toSet()
}
