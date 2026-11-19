package com.pynapli.server.verification

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.grid.GridService
import com.pynapli.server.grid.JdbcGridRepository
import com.pynapli.server.grid.insertActiveProfileLink
import com.pynapli.server.grid.insertBlock
import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.grid.insertHiddenProfile
import com.pynapli.server.grid.markProfileVerified
import com.pynapli.server.grid.setRiskState
import com.pynapli.server.safety.JdbcBlockRepository
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Verified couples only" (FILTERS_SPEC Verified) on Grid and Swipe. The filter must mean exactly
 * the couple-verified badge for this viewer: every card it returns shows the badge, and every card
 * that shows the badge is returned. The predicate is exercised at repository level; the
 * thin-deck tests go through SwipeService / GridService to prove relaxation and widening never
 * drop it (founder rule 2026-11-18).
 */
class VerifiedCouplesFilterIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private val filter = DiscoveryFilters(verifiedCouplesOnly = true)

    @Test
    fun gridReturnsExactlyTheCouplesThatShowTheBadge() =
        runBlocking {
            val world = seedWorld()
            // Unverified viewer: J1's verified_only partner is not visible to them.
            assertEquals(world.expectedGrid, gridIds(world.viewer, filter))
            assertEquals(world.expectedGrid, gridBadgeIds(world))

            // Once the viewer is verified, J1's partner (and so J1's badge) becomes visible.
            dataSource.markProfileVerified(world.viewer)
            assertEquals(world.expectedGrid + world.j1, gridIds(world.viewer, filter))
            assertEquals(world.expectedGrid + world.j1, gridBadgeIds(world))
        }

    @Test
    fun swipeReturnsExactlyTheCouplesThatShowTheBadge() =
        runBlocking {
            val world = seedWorld()
            assertEquals(world.expectedSwipe, swipeIds(world, filter))
            assertEquals(world.expectedSwipe, swipeBadgeIds(world))

            dataSource.markProfileVerified(world.viewer)
            assertEquals(world.expectedSwipe + world.j1, swipeIds(world, filter))
            assertEquals(world.expectedSwipe + world.j1, swipeBadgeIds(world))
        }

    @Test
    fun filterOffChangesNothing() =
        runBlocking {
            val world = seedWorld()
            val grid = gridIds(world.viewer, DiscoveryFilters())
            val swipe = swipeIds(world, DiscoveryFilters())
            // Singles, half-verified couples and pending pairs are all still there without the filter.
            listOf(world.b1, world.b2, world.c, world.d1, world.d2, world.e1).forEach { id ->
                assertTrue(id in grid, "grid without the filter must keep $id")
                assertTrue(id in swipe, "swipe without the filter must keep $id")
            }
        }

    @Test
    fun combinesWithLinkedOnlyAndVerifiedOnlyOnBothSurfaces() =
        runBlocking {
            val world = seedWorld()
            // linkedOnly=true routes Swipe through listLinkedCandidateUserIdsNear + a forced id list.
            val linked = DiscoveryFilters(linkedOnly = true, verifiedCouplesOnly = true)
            assertEquals(world.expectedGrid, gridIds(world.viewer, linked))
            assertEquals(world.expectedSwipe, swipeIds(world, linked))

            // "Not linked" and "verified couple" cannot both hold.
            val unlinked = DiscoveryFilters(linkedOnly = false, verifiedCouplesOnly = true)
            assertEquals(emptySet(), gridIds(world.viewer, unlinked))
            assertEquals(emptySet(), swipeIds(world, unlinked))

            // Everyone in a verified couple is individually verified, so verifiedOnly changes nothing.
            val verified = DiscoveryFilters(verifiedOnly = true, verifiedCouplesOnly = true)
            assertEquals(world.expectedGrid, gridIds(world.viewer, verified))
            assertEquals(world.expectedSwipe, swipeIds(world, verified))
        }

    @Test
    fun viewersWithAndWithoutTheFilterNeverShareARankWindow() =
        runBlocking {
            val world = seedWorld()
            val neighbour = UUID.randomUUID()
            // Same location cell as the viewer, so both share a cache key on the cached path.
            dataSource.insertDiscoverableUser(neighbour, "Neighbour", age = 40, longitude = -122.08412, latitude = 37.42201)
            val ttl = Duration.ofMinutes(5)

            suspend fun gridOf(
                repository: JdbcGridRepository,
                viewer: UUID,
                filters: DiscoveryFilters,
            ): Set<UUID> = ids(repository.queryNearby(viewer, RADIUS, 60, filters).cards.map { it.userId })

            suspend fun swipeOf(
                repository: JdbcSwipeRepository,
                viewer: UUID,
                filters: DiscoveryFilters,
            ): Set<UUID> = ids(repository.queryDeck(viewer, RADIUS, 30, filters, setOf(world.h2)).cards.map { it.userId })

            // Unfiltered viewer fills the window first; the filtered viewer must not be served from it.
            val grid = JdbcGridRepository(dataSource, rankCacheTtl = ttl)
            assertTrue(world.c in gridOf(grid, neighbour, DiscoveryFilters()))
            assertEquals(world.expectedGrid, gridOf(grid, world.viewer, filter))
            val swipe = JdbcSwipeRepository(dataSource, rankCacheTtl = ttl)
            assertTrue(world.c in swipeOf(swipe, neighbour, DiscoveryFilters()))
            assertEquals(world.expectedSwipe, swipeOf(swipe, world.viewer, filter))

            // Filtered viewer first; the unfiltered viewer must still see everyone.
            val gridReversed = JdbcGridRepository(dataSource, rankCacheTtl = ttl)
            assertEquals(world.expectedGrid, gridOf(gridReversed, world.viewer, filter))
            assertTrue(world.c in gridOf(gridReversed, neighbour, DiscoveryFilters()))
            val swipeReversed = JdbcSwipeRepository(dataSource, rankCacheTtl = ttl)
            assertEquals(world.expectedSwipe, swipeOf(swipeReversed, world.viewer, filter))
            assertTrue(world.c in swipeOf(swipeReversed, neighbour, DiscoveryFilters()))
        }

    @Test
    fun aThinSwipeDeckNeverRelaxesTheFilterWithOrWithoutTheVerifiedLock() =
        runBlocking {
            val world = seedWorld()
            val service =
                SwipeService(JdbcSwipeRepository(dataSource), blockRepository = JdbcBlockRepository(dataSource))
            val linkedLocks = setOf(SwipeDealbreakerKey.VERIFIED, SwipeDealbreakerKey.LINKED_ONLY)
            val expected = world.expectedSwipe.map { it.toString() }.toSet()
            // Five matches against a low-water mark of ten, and a radius below the maximum, so the
            // relaxed retry (filters + doubled radius) runs every time.
            val base = SwipeDeckRequestDto(radiusMeters = 25_000, limit = 30, verifiedCouplesOnly = true)
            val requests =
                listOf(
                    base,
                    base.copy(dealbreakers = setOf(SwipeDealbreakerKey.VERIFIED)),
                    // Linked-only path (listLinkedCandidateUserIdsNear + forced ids); its relaxed retry
                    // drops linkedOnly and runs the normal path, still with the filter.
                    base.copy(linkedOnly = true),
                    base.copy(linkedOnly = true, dealbreakers = linkedLocks),
                )
            requests.forEach { request ->
                val items = service.queryDeck(world.viewer, request).items
                assertTrue(items.all { it.coupleVerified }, "every card must show the couple badge: $request")
                assertEquals(expected, items.map { it.userId }.toSet(), "$request")
            }
        }

    @Test
    fun aThinSwipeDeckStillRelaxesVerifiedOnlyAsBefore() =
        runBlocking {
            val viewer = person("Viewer")
            val verified = person("Vera").also { dataSource.markProfileVerified(it) }
            val unverified = person("Una")
            val service = SwipeService(JdbcSwipeRepository(dataSource))

            // One verified match is below the low-water mark, so the unlocked filter relaxes.
            val unlocked = SwipeDeckRequestDto(radiusMeters = 25_000, limit = 30, verifiedOnly = true)
            val relaxed =
                service
                    .queryDeck(viewer, unlocked)
                    .items
                    .map { it.userId }
                    .toSet()
            assertEquals(setOf(verified, unverified).map { it.toString() }.toSet(), relaxed)

            // Locked (with distance), it holds.
            val locked =
                SwipeDeckRequestDto(
                    radiusMeters = 25_000,
                    limit = 30,
                    verifiedOnly = true,
                    dealbreakers = setOf(SwipeDealbreakerKey.VERIFIED, SwipeDealbreakerKey.DISTANCE),
                )
            assertEquals(listOf(verified.toString()), service.queryDeck(viewer, locked).items.map { it.userId })
        }

    @Test
    fun gridWideningKeepsTheFilter() =
        runBlocking {
            val viewer = person("Viewer")
            // Near: an unverified couple. Far (~30 km): a verified couple and an unverified couple.
            couple("Near", verified1 = false, verified2 = false)
            val (far1, far2) = couple("Far", verified1 = true, verified2 = true, longitudeShift = FAR_SHIFT)
            couple("FarUnverified", verified1 = false, verified2 = false, longitudeShift = FAR_SHIFT)
            val service = GridService(JdbcGridRepository(dataSource))

            // Nothing within 5 km passes, so GridService widens to the maximum radius with the same filters.
            val request = GridQueryRequestDto(radiusMeters = 5_000, verifiedCouplesOnly = true)
            val widened = service.queryGrid(viewer, request)
            assertEquals(setOf(far1, far2).map { it.toString() }.toSet(), widened.items.map { it.userId }.toSet())
            assertTrue(widened.items.all { it.coupleVerified })
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private class World(
        val viewer: UUID,
        val a1: UUID,
        val a2: UUID,
        val b1: UUID,
        val b2: UUID,
        val c: UUID,
        val d1: UUID,
        val d2: UUID,
        val e1: UUID,
        val e2: UUID,
        val g1: UUID,
        val g2: UUID,
        val h2: UUID,
        val j1: UUID,
        val j2: UUID,
        val m2: UUID,
        val hiddenByViewer: Set<UUID>,
    ) {
        // Grid's repository does not drop people the viewer hid (GridService does), so G2 is here.
        val expectedGrid: Set<UUID> get() = setOf(a1, a2, e2, g2, j2, m2)

        // Swipe's repository drops hidden targets itself (G2), and H2 is excluded as GridService /
        // SwipeDeckQueryAction exclude a blocked user.
        val expectedSwipe: Set<UUID> get() = setOf(a1, a2, e2, j2, m2)
    }

    /**
     * Pairs that each test one part of the badge definition. Each pair is linked both ways (as
     * accepting a link does) unless the scenario says otherwise.
     */
    private fun seedWorld(): World {
        val viewer = person("Viewer")
        // A: both verified -> both partners show the badge.
        val (a1, a2) = couple("A", verified1 = true, verified2 = true)
        // B: one partner verified -> neither does.
        val (b1, b2) = couple("B", verified1 = true, verified2 = false)
        // C: a verified single.
        val c = person("C").also { dataSource.markProfileVerified(it) }
        // D: both verified, link still pending.
        val d1 = person("D1").also { dataSource.markProfileVerified(it) }
        val d2 = person("D2").also { dataSource.markProfileVerified(it) }
        dataSource.insertPendingLink(d1, d2)
        // E: E1 hides their partner from everyone -> E1 has no badge; E2 (public) keeps theirs.
        val (e1, e2) = couple("E", verified1 = true, verified2 = true)
        dataSource.setCouplePartnerVisibility(e1, "hidden")
        // F: F2 is terminated (banned) -> F1's partner is not visible; F2 is not discoverable.
        val (_, f2) = couple("F", verified1 = true, verified2 = true)
        dataSource.setRiskState(f2, score = 100, tier = "terminate")
        // G: the viewer hid G2 -> G1 loses the badge (cards strip a hidden partner).
        val (g1, g2) = couple("G", verified1 = true, verified2 = true)
        dataSource.insertHiddenProfile(viewer, g2)
        // H: the viewer blocked H2 -> H1's partner is not visible.
        val (_, h2) = couple("H", verified1 = true, verified2 = true)
        dataSource.insertBlock(viewer, h2)
        // I: I2 is incognito -> I1's partner is not visible.
        val (_, i2) = couple("I", verified1 = true, verified2 = true)
        dataSource.runSql("UPDATE discovery_settings SET incognito = true WHERE user_id = ?", i2)
        // J: J1 shows their partner to verified members only.
        val (j1, j2) = couple("J", verified1 = true, verified2 = true)
        dataSource.setCouplePartnerVisibility(j1, "verified_only")
        // K: K2's account is deleted.
        val (_, k2) = couple("K", verified1 = true, verified2 = true)
        dataSource.runSql("UPDATE users SET deleted_at = now() WHERE id = ?", k2)
        // M: M1 shows their partner to matches only, and the viewer has no match with M1.
        val (m1, m2) = couple("M", verified1 = true, verified2 = true)
        dataSource.setCouplePartnerVisibility(m1, "matches_only")
        // U: both verified, linked once and since unlinked -> neither.
        val (u1, u2) = couple("U", verified1 = true, verified2 = true)
        dataSource.runSql(
            "UPDATE user_profile_links SET status = 'unlinked', unlinked_at = now() WHERE user_id IN (?, ?)",
            u1,
            u2,
        )
        return World(viewer, a1, a2, b1, b2, c, d1, d2, e1, e2, g1, g2, h2, j1, j2, m2, hiddenByViewer = setOf(g2))
    }

    private var nextOffset = 0

    private fun person(
        name: String,
        longitudeShift: Double = 0.0,
    ): UUID {
        val id = UUID.randomUUID()
        nextOffset += 1
        // Within a few hundred metres of the viewer unless shifted.
        val longitude = -122.0841 + nextOffset * 0.0002 + longitudeShift
        dataSource.insertDiscoverableUser(id, name, age = 35, longitude = longitude, latitude = 37.4220)
        return id
    }

    private fun couple(
        name: String,
        verified1: Boolean,
        verified2: Boolean,
        longitudeShift: Double = 0.0,
    ): Pair<UUID, UUID> {
        val first = person("${name}1", longitudeShift)
        val second = person("${name}2", longitudeShift)
        dataSource.insertActiveProfileLink(first, second)
        dataSource.insertActiveProfileLink(second, first)
        if (verified1) dataSource.markProfileVerified(first)
        if (verified2) dataSource.markProfileVerified(second)
        return first to second
    }

    // --- queries ------------------------------------------------------------------------------------

    // Fresh repositories per read: each holds a short in-process rank-window cache.
    private suspend fun gridIds(
        viewer: UUID,
        filters: DiscoveryFilters,
    ): Set<UUID> = ids(JdbcGridRepository(dataSource).queryNearby(viewer, RADIUS, 60, filters).cards.map { it.userId })

    /**
     * The Grid badge as the viewer sees it: GridService drops blocked people (H2) and strips a
     * partner the viewer hid before the page is returned.
     */
    private suspend fun gridBadgeIds(world: World): Set<UUID> =
        JdbcGridRepository(dataSource)
            .queryNearby(world.viewer, RADIUS, 60, DiscoveryFilters())
            .cards
            .filter { it.userId != world.h2 }
            .filter { it.coupleVerified && it.linkedPartnerUserId !in world.hiddenByViewer }
            .map { it.userId }
            .toSet()

    private suspend fun swipeIds(
        world: World,
        filters: DiscoveryFilters,
    ): Set<UUID> =
        ids(
            JdbcSwipeRepository(dataSource)
                .queryDeck(world.viewer, RADIUS, 30, filters, excludedTargetUserIds = setOf(world.h2))
                .cards
                .map { it.userId },
        )

    /** The Swipe badge exactly as SwipeDtoMapper computes it. */
    private suspend fun swipeBadgeIds(world: World): Set<UUID> =
        JdbcSwipeRepository(dataSource)
            .queryDeck(world.viewer, RADIUS, 30, DiscoveryFilters(), excludedTargetUserIds = setOf(world.h2))
            .cards
            .filter { it.verified && it.linkedPartnerUserId != null && it.linkedPartnerVerified }
            .map { it.userId }
            .toSet()

    private fun ids(list: List<UUID>): Set<UUID> {
        assertEquals(list.distinct(), list, "no duplicates")
        return list.toSet()
    }

    private companion object {
        const val RADIUS = 100_000

        // About 30 km east at this latitude: outside 5 km, inside the 100 km widened Grid radius.
        const val FAR_SHIFT = 0.34
    }
}

private fun DataSource.insertPendingLink(
    userId: UUID,
    linkedUserId: UUID,
) {
    runSql(
        "INSERT INTO user_profile_links (id, user_id, linked_user_id, status) VALUES (?, ?, ?, 'pending')",
        UUID.randomUUID(),
        userId,
        linkedUserId,
    )
}

private fun DataSource.setCouplePartnerVisibility(
    userId: UUID,
    value: String,
) = runSql("UPDATE discovery_settings SET partner_visibility = ? WHERE user_id = ?", value, userId)

private fun DataSource.runSql(
    statement: String,
    vararg params: Any,
) {
    connection.use { connection ->
        connection.prepareStatement(statement).use { prepared ->
            params.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
            prepared.executeUpdate()
        }
    }
}
