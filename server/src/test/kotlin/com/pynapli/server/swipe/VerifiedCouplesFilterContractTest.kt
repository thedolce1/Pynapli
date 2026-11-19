package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.grid.SavedGridFilterService
import com.pynapli.server.grid.gridRankCacheKey
import com.pynapli.server.grid.isGridRankCacheEligible
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How "Verified couples only" (`verifiedCouplesOnly`) travels through the discovery plumbing:
 * cache class, cursor / deck scope, relaxation, entitlement, parsing and saved filters.
 */
class VerifiedCouplesFilterContractTest {
    private val on = DiscoveryFilters(verifiedCouplesOnly = true)
    private val viewer = UUID.randomUUID()

    @Test
    fun theFilterIsNeverServedFromASharedRankWindowAndIsPartOfTheKeyAnyway() {
        // Viewer-specific (blocks, partner visibility, hidden list), like linkedOnly.
        assertFalse(on.isGridRankCacheEligible())
        assertFalse(on.isRankCacheEligible())
        assertTrue(DiscoveryFilters().isGridRankCacheEligible())
        assertTrue(DiscoveryFilters().isRankCacheEligible())
        assertNotEquals(DiscoveryFilters().gridRankCacheKey(), on.gridRankCacheKey())
        assertNotEquals(DiscoveryFilters().rankCacheKey(), on.rankCacheKey())
    }

    @Test
    fun aFilterChangeInvalidatesGridCursorsAndSwipeDeckTokens() {
        fun grid(filters: DiscoveryFilters) = DiscoveryCursorCodec.gridScopeHash(viewer, 50_000, 60, filters, onlineOnly = false)

        fun deck(filters: DiscoveryFilters) = SwipeDeckTokenCodec.deckScopeHash(viewer, 50_000, 30, filters)

        assertNotEquals(grid(DiscoveryFilters()), grid(on))
        assertNotEquals(deck(DiscoveryFilters()), deck(on))
        // Distinct from linkedOnly, which it implies but does not set.
        assertNotEquals(grid(DiscoveryFilters(linkedOnly = true)), grid(on))
        assertNotEquals(deck(DiscoveryFilters(linkedOnly = true)), deck(on))
    }

    @Test
    fun persistedDeckPayloadCarriesTheFieldOnlyWhenSet() {
        assertFalse("verifiedCouplesOnly" in DiscoveryFilters().toPersistedJson())
        assertTrue("\"verifiedCouplesOnly\":true" in on.toPersistedJson())
    }

    @Test
    fun neverRelaxesLockedOrNot() {
        // Founder rule 2026-11-18: a trust filter, never relaxed. On its own (radius at max) it gives
        // a thin deck nothing to relax.
        assertFalse(SwipeDeckRelaxation.hasRelaxableFilters(on, 100_000, 100_000, dealbreakers = emptySet()))
        val everyStep = listOf(emptySet<String>(), setOf(SwipeDealbreakerKey.VERIFIED), SwipeDealbreakerKey.ALL)
        everyStep.forEach { locks ->
            assertTrue(SwipeDeckRelaxation.relaxFilters(on, dealbreakers = locks).verifiedCouplesOnly)
        }
        // Relaxing everything else around it (linkedOnly, verifiedOnly, intents, radius) still keeps it.
        val busy = on.copy(linkedOnly = true, verifiedOnly = true, intents = setOf("dating"))
        assertTrue(SwipeDeckRelaxation.hasRelaxableFilters(busy, 25_000, 100_000, dealbreakers = emptySet()))
        val relaxed = SwipeDeckRelaxation.relaxFilters(busy, dealbreakers = emptySet())
        assertTrue(relaxed.verifiedCouplesOnly)
        assertNull(relaxed.linkedOnly)
        assertFalse(relaxed.verifiedOnly)
    }

    @Test
    fun verifiedOnlyStillRelaxesAsBefore() {
        val verifiedOnly = DiscoveryFilters(verifiedOnly = true)
        assertTrue(SwipeDeckRelaxation.hasRelaxableFilters(verifiedOnly, 100_000, 100_000, dealbreakers = emptySet()))
        assertFalse(SwipeDeckRelaxation.relaxFilters(verifiedOnly, dealbreakers = emptySet()).verifiedOnly)
        val locked = setOf(SwipeDealbreakerKey.VERIFIED)
        assertTrue(SwipeDeckRelaxation.relaxFilters(verifiedOnly, dealbreakers = locked).verifiedOnly)
    }

    @Test
    fun isFreeLikeVerifiedOnly() {
        assertTrue(on.hasAdvancedFilters)
        assertFalse(on.requiresAdvancedEntitlement)
        assertEquals(DiscoveryFilters(verifiedOnly = true).requiresAdvancedEntitlement, on.requiresAdvancedEntitlement)
    }

    @Test
    fun parsingKeepsLinkedOnlyAsSent() {
        val parsed =
            discoveryFiltersFrom(
                minimumAge = null,
                maximumAge = null,
                relationshipContexts = emptyList(),
                linkedOnly = null,
                verifiedCouplesOnly = true,
            ) { IllegalStateException(it) }
        assertTrue(parsed.verifiedCouplesOnly)
        assertNull(parsed.linkedOnly)

        val swipe =
            SwipeDeckRequestParser.parse(SwipeDeckRequestDto(verifiedCouplesOnly = true), 1_000, 100_000, 50)
        assertTrue(swipe.filters.verifiedCouplesOnly)
        assertNull(swipe.filters.linkedOnly)
    }

    @Test
    fun savedFiltersWithoutTheFieldStillLoadAndNewOnesRoundTrip() =
        runBlocking {
            // Rows saved before the field existed.
            val oldSwipe = OneRowRepository("""{"radiusMeters":5000,"verifiedOnly":true}""")
            val swipeItem = SavedSwipeFilterService(oldSwipe).list(viewer).items.single()
            assertFalse(swipeItem.payload.verifiedCouplesOnly)
            assertTrue(swipeItem.payload.verifiedOnly)

            val oldGrid = OneRowRepository("""{"radiusMeters":5000,"linkedOnly":true}""")
            val gridItem = SavedGridFilterService(oldGrid).list(viewer).items.single()
            assertFalse(gridItem.payload.verifiedCouplesOnly)
            assertEquals(true, gridItem.payload.linkedOnly)

            // New rows keep it.
            val swipeRequest =
                CreateSavedSwipeFilterRequestDto(name = "Couples", payload = SwipeDeckRequestDto(verifiedCouplesOnly = true))
            val savedSwipe = SavedSwipeFilterService(OneRowRepository("{}")).create(viewer, swipeRequest)
            assertTrue(savedSwipe.payload.verifiedCouplesOnly)
            val gridRequest =
                CreateSavedGridFilterRequestDto(name = "Couples", payload = GridQueryRequestDto(verifiedCouplesOnly = true))
            val savedGrid = SavedGridFilterService(OneRowRepository("{}")).create(viewer, gridRequest)
            assertTrue(savedGrid.payload.verifiedCouplesOnly)
        }

    /** Lists one stored row with [listedPayload]; create echoes what it was given. */
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
