package com.pynapli.app.grid

import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan G9: a paid filter clears only itself, and filters survive a cold start (locally). */
@OptIn(ExperimentalCoroutinesApi::class)
class GridPaidFiltersAndPersistenceTest {
    private val paidAndFree =
        GridFilters(
            online = true,
            lifestyleTags = setOf(LifestyleTag.SoftSwap),
            logisticsTags = setOf(LogisticsTag.Hosts, LogisticsTag.PublicMeetFirst),
        )

    @Test
    fun theClientPaidSetMatchesTheServerPredicate() {
        assertTrue(GridFilters(lifestyleTags = setOf(LifestyleTag.Enm)).requiresAdvancedEntitlement)
        assertTrue(GridFilters(logisticsTags = setOf(LogisticsTag.Travels)).requiresAdvancedEntitlement)
        assertFalse(GridFilters(logisticsTags = setOf(LogisticsTag.PublicMeetFirst)).requiresAdvancedEntitlement)
        assertFalse(GridFilters(online = true, fresh = true, verified = true).requiresAdvancedEntitlement)
        assertEquals(
            GridFilters(online = true, logisticsTags = setOf(LogisticsTag.PublicMeetFirst)),
            paidAndFree.withoutPaidFilters(),
        )
    }

    @Test
    fun aKnownFreeViewerChoosingAPaidFilterKeepsTheRestAndOpensThePaywall() =
        runTest {
            val repository = RecordingGridFeedRepository()
            val holder = GridStateHolder(repository)
            val events = mutableListOf<GridEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.toList(events) }
            holder.setAdvancedFiltersAllowed(false)
            holder.load()

            holder.applyFilters(paidAndFree)

            val asked = repository.requests.last()
            assertTrue(asked.online, "the free choices still apply")
            assertEquals(setOf(LogisticsTag.PublicMeetFirst), asked.logisticsTags)
            assertTrue(asked.lifestyleTags.isEmpty(), "only the paid filter is cleared")
            assertEquals(2, repository.requests.size, "no refused round trip")
            val state = holder.state.value
            assertEquals(asked, state.filters)
            assertEquals(PAID_FILTER_PAYWALL_MESSAGE, state.paywallMessage, "the prompt stays after the answer")
            assertEquals(SubscriptionTier.SOCIAL, state.paywallUpgradeTier)
            assertEquals(listOf<GridEvent>(GridEvent.OpenPaywall), events)
        }

    @Test
    fun aServerRefusalAlsoClearsOnlyThePaidFilterAndReasksForTheRest() =
        runTest {
            val repository = RecordingGridFeedRepository(refuseAdvanced = true)
            val holder = GridStateHolder(repository)
            val events = mutableListOf<GridEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.toList(events) }
            // Entitlement unknown (plan not loaded): the server decides.
            holder.load()

            holder.applyFilters(paidAndFree)

            assertEquals(3, repository.requests.size, "first load, the refused ask, the re-ask")
            val reasked = repository.requests.last()
            assertTrue(reasked.online)
            assertFalse(reasked.requiresAdvancedEntitlement)
            val state = holder.state.value
            assertEquals(reasked, state.filters, "not reset to defaults")
            assertEquals(PAID_FILTER_PAYWALL_MESSAGE, state.paywallMessage)
            assertNull(state.errorMessage)
            assertEquals(listOf("a"), state.cards.map { it.id })
            assertEquals(listOf<GridEvent>(GridEvent.OpenPaywall), events)
        }

    @Test
    fun filtersComeBackOnTheNextColdStartAndSignOutForgetsThem() =
        runTest {
            val store = InMemoryGridFilterStore()
            val first = GridStateHolder(RecordingGridFeedRepository(), filterStore = store)
            first.load()
            first.toggleQuickFilter(QuickFilterId.Fresh)
            first.toggleQuickFilter(QuickFilterId.Verified)
            assertEquals(GridFilters(fresh = true, verified = true), store.saved)

            // Cold start: a new holder over the same store asks with the saved filters straight away.
            val repository = RecordingGridFeedRepository()
            val second = GridStateHolder(repository, filterStore = store)
            second.primeFromCacheThenLoad()
            assertEquals(GridFilters(fresh = true, verified = true), repository.requests.single())
            assertEquals(GridFilters(fresh = true, verified = true), second.state.value.filters)

            second.clearForSignOut()
            assertNull(store.saved, "sign-out clears the stored filters")
            second.load()
            assertEquals(GridFilters.defaults, repository.requests.last(), "the next account starts clean")
        }

    @Test
    fun aStoredBlobRoundTripsAndACorruptOneReadsAsNothing() {
        val filters = GridFilters(online = true, fresh = true, radiusMeters = 10_000, minimumAge = 30, maximumAge = 45)
        assertEquals(filters, decodeGridFilters(encodeGridFilters(filters)))
        assertNull(decodeGridFilters("{not json"))
        assertNull(decodeGridFilters(null))
        assertFalse(encodeGridFilters(filters).contains("cursor\":\""), "no cursor is stored")
    }

    @Test
    fun planTiersMapToPaidFilterAccess() {
        assertNull(GridFilters.advancedFiltersAllowedFor(null), "unknown until the plan loads")
        assertEquals(false, GridFilters.advancedFiltersAllowedFor(SubscriptionTier.FREE))
        assertEquals(true, GridFilters.advancedFiltersAllowedFor(SubscriptionTier.SOCIAL))
        assertEquals(true, GridFilters.advancedFiltersAllowedFor(SubscriptionTier.SELECT))
    }
}

/** Records the filters each first-page request asked with; optionally refuses paid filters like the server. */
private class RecordingGridFeedRepository(
    private val refuseAdvanced: Boolean = false,
) : GridFeedRepository {
    val requests = mutableListOf<GridFilters>()

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        requests += filters
        if (refuseAdvanced && filters.requiresAdvancedEntitlement) {
            throw GridFeedException(code = "ENTITLEMENT_REQUIRED", message = "Upgrade required")
        }
        return GridFeedUi(cards = listOf(GridProfileCardUi(id = "a", displayName = "A", online = false, distanceLabel = null, tone = GridCardTone.Warm)))
    }
}

private class InMemoryGridFilterStore : GridFilterStore {
    var saved: GridFilters? = null
        private set

    override suspend fun load(): GridFilters? = saved

    override suspend fun save(filters: GridFilters) {
        saved = filters
    }

    override suspend fun clear() {
        saved = null
    }
}
