package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.PartnerFilterSql
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.grid.SavedGridFilterService
import com.pynapli.server.grid.gridRankCacheKey
import com.pynapli.server.grid.isGridRankCacheEligible
import com.pynapli.server.savedfilters.SavedFilterException
import com.pynapli.server.savedfilters.SavedFilterRecord
import com.pynapli.server.savedfilters.SavedFilterRepository
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Plan G2-3: how the partner filters and "No single men" travel through the discovery plumbing:
 * cache class, cursor / deck scope, persisted payload, relaxation, entitlement, parsing, saved filters.
 */
class PartnerFiltersContractTest {
    private val viewer = UUID.randomUUID()
    private val each: List<DiscoveryFilters> =
        listOf(
            DiscoveryFilters(partnerGenders = setOf("woman")),
            DiscoveryFilters(partnerMinimumAge = 30),
            DiscoveryFilters(partnerMaximumAge = 40),
            DiscoveryFilters(partnerOrientations = setOf("bisexual")),
            DiscoveryFilters(partnerVerifiedOnly = true),
            DiscoveryFilters(excludeSingleMen = true),
        )

    @Test
    fun neverServedFromASharedRankWindowAndKeyedAnyway() {
        each.forEach { filters ->
            assertFalse(filters.isGridRankCacheEligible(), "$filters")
            assertFalse(filters.isRankCacheEligible(), "$filters")
            assertNotEquals(DiscoveryFilters().gridRankCacheKey(), filters.gridRankCacheKey())
            assertNotEquals(DiscoveryFilters().rankCacheKey(), filters.rankCacheKey())
        }
        assertTrue(DiscoveryFilters().isGridRankCacheEligible())
        assertTrue(DiscoveryFilters().isRankCacheEligible())
    }

    @Test
    fun aFilterChangeInvalidatesGridCursorsAndSwipeDeckTokensButOldScopesKeepTheirHash() {
        fun grid(filters: DiscoveryFilters) = DiscoveryCursorCodec.gridScopeHash(viewer, 50_000, 60, filters, onlineOnly = false)

        fun deck(filters: DiscoveryFilters) = SwipeDeckTokenCodec.deckScopeHash(viewer, 50_000, 30, filters)

        val gridHashes = each.map(::grid).toSet()
        val deckHashes = each.map(::deck).toSet()
        assertEquals(each.size, gridHashes.size)
        assertEquals(each.size, deckHashes.size)
        assertFalse(grid(DiscoveryFilters()) in gridHashes)
        assertFalse(deck(DiscoveryFilters()) in deckHashes)
        // Nothing appended when unset, so a cursor issued before G2-3 still decodes.
        assertEquals("", DiscoveryFilters().partnerScopeKey)
        assertNotEquals(grid(DiscoveryFilters(partnerGenders = setOf("woman"))), grid(DiscoveryFilters(partnerGenders = setOf("man"))))
    }

    @Test
    fun persistedDeckPayloadCarriesThemOnlyWhenSet() {
        assertFalse("partner" in DiscoveryFilters().toPersistedJson())
        assertFalse("excludeSingleMen" in DiscoveryFilters().toPersistedJson())
        assertTrue("\"partnerGenders\":[\"woman\"]" in DiscoveryFilters(partnerGenders = setOf("woman")).toPersistedJson())
        assertTrue("\"excludeSingleMen\":true" in DiscoveryFilters(excludeSingleMen = true).toPersistedJson())
    }

    @Test
    fun neverRelaxed() {
        val busy =
            DiscoveryFilters(
                linkedOnly = true,
                partnerGenders = setOf("woman"),
                partnerMinimumAge = 25,
                partnerMaximumAge = 45,
                partnerOrientations = setOf("bisexual"),
                partnerVerifiedOnly = true,
                excludeSingleMen = true,
            )
        // On their own (radius at max) they give a thin deck nothing to relax.
        assertFalse(SwipeDeckRelaxation.hasRelaxableFilters(busy.copy(linkedOnly = null), 100_000, 100_000, emptySet()))
        listOf(emptySet<String>(), SwipeDealbreakerKey.ALL).forEach { locks ->
            val relaxed = SwipeDeckRelaxation.relaxFilters(busy, locks)
            assertEquals(busy.partnerGenders, relaxed.partnerGenders)
            assertEquals(25, relaxed.partnerMinimumAge)
            assertEquals(45, relaxed.partnerMaximumAge)
            assertEquals(busy.partnerOrientations, relaxed.partnerOrientations)
            assertTrue(relaxed.partnerVerifiedOnly)
            assertTrue(relaxed.excludeSingleMen)
        }
    }

    @Test
    fun allFree() {
        each.forEach { assertFalse(it.requiresAdvancedEntitlement, "$it") }
    }

    @Test
    fun parsingValidatesLikeTheOwnersFilters() {
        fun parse(
            genders: List<String> = emptyList(),
            min: Int? = null,
            max: Int? = null,
            orientations: List<String> = emptyList(),
        ) = discoveryFiltersFrom(
            minimumAge = null,
            maximumAge = null,
            relationshipContexts = emptyList(),
            linkedOnly = null,
            partnerGenders = genders,
            partnerMinimumAge = min,
            partnerMaximumAge = max,
            partnerOrientations = orientations,
        ) { IllegalArgumentException(it) }

        assertEquals(setOf("woman"), parse(genders = listOf(" Woman ")).partnerGenders)
        assertFailsWith<IllegalArgumentException> { parse(genders = listOf("self_describe")) }
        assertFailsWith<IllegalArgumentException> { parse(genders = listOf("couple")) }
        assertFailsWith<IllegalArgumentException> { parse(orientations = listOf("anything")) }
        assertFailsWith<IllegalArgumentException> { parse(min = 17) }
        assertFailsWith<IllegalArgumentException> { parse(min = 40, max = 30) }
        // Partner filters never set linkedOnly; they imply it in SQL.
        assertEquals(null, parse(genders = listOf("woman")).linkedOnly)
        assertTrue(PartnerFilterSql.appliesTo(parse(genders = listOf("woman"))))
        assertFalse(PartnerFilterSql.appliesTo(DiscoveryFilters()))

        val swipe =
            SwipeDeckRequestParser.parse(
                SwipeDeckRequestDto(partnerOrientations = listOf("bisexual"), excludeSingleMen = true),
                1_000,
                100_000,
                50,
            )
        assertEquals(setOf("bisexual"), swipe.filters.partnerOrientations)
        assertTrue(swipe.filters.excludeSingleMen)
    }

    @Test
    fun savedFiltersRoundTripAndOldRowsLoad() =
        runBlocking<Unit> {
            val oldGrid = OneRowRepository("""{"radiusMeters":5000,"linkedOnly":true}""")
            val oldItem = SavedGridFilterService(oldGrid).list(viewer).items.single()
            assertTrue(oldItem.payload.partnerGenders.isEmpty())
            assertFalse(oldItem.payload.excludeSingleMen)

            val gridPayload =
                GridQueryRequestDto(
                    partnerGenders = listOf("woman"),
                    partnerMinimumAge = 25,
                    partnerMaximumAge = 40,
                    partnerOrientations = listOf("bisexual"),
                    partnerVerifiedOnly = true,
                    excludeSingleMen = true,
                )
            val savedGrid =
                SavedGridFilterService(OneRowRepository("{}")).create(viewer, CreateSavedGridFilterRequestDto("Couples", gridPayload))
            assertEquals(gridPayload.partnerGenders, savedGrid.payload.partnerGenders)
            assertEquals(25, savedGrid.payload.partnerMinimumAge)
            assertEquals(40, savedGrid.payload.partnerMaximumAge)
            assertEquals(gridPayload.partnerOrientations, savedGrid.payload.partnerOrientations)
            assertTrue(savedGrid.payload.partnerVerifiedOnly)
            assertTrue(savedGrid.payload.excludeSingleMen)

            val swipePayload = SwipeDeckRequestDto(partnerGenders = listOf("man"), excludeSingleMen = true)
            val savedSwipe =
                SavedSwipeFilterService(OneRowRepository("{}")).create(viewer, CreateSavedSwipeFilterRequestDto("Couples", swipePayload))
            assertEquals(listOf("man"), savedSwipe.payload.partnerGenders)
            assertTrue(savedSwipe.payload.excludeSingleMen)

            assertFailsWith<SavedFilterException> {
                SavedGridFilterService(OneRowRepository("{}"))
                    .create(viewer, CreateSavedGridFilterRequestDto("Bad", GridQueryRequestDto(partnerGenders = listOf("self_describe"))))
            }
        }

    private class OneRowRepository(
        private val listedPayload: String,
    ) : SavedFilterRepository {
        override suspend fun list(userId: UUID): List<SavedFilterRecord> = listOf(record(UUID.randomUUID(), userId, "Old", listedPayload))

        override suspend fun create(
            id: UUID,
            userId: UUID,
            name: String,
            payloadJson: String,
        ): SavedFilterRecord = record(id, userId, name, payloadJson)

        override suspend fun delete(
            userId: UUID,
            id: UUID,
        ): Boolean = false

        override suspend fun countForUser(userId: UUID): Int = 0

        private fun record(
            id: UUID,
            userId: UUID,
            name: String,
            payloadJson: String,
        ) = SavedFilterRecord(id, userId, name, payloadJson, CREATED, CREATED)

        private companion object {
            val CREATED: Instant = Instant.parse("2026-11-01T00:00:00Z")
        }
    }
}
