package com.pynapli.app.grid

import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
    fun aRefusedPaidFilterClearsOnlyItselfReasksForTheRestAndOpensThePaywall() =
        runTest {
            val repository = RecordingGridFeedRepository(refuseAdvanced = true)
            val holder = GridStateHolder(repository)
            val events = mutableListOf<GridEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.toList(events) }
            holder.load()

            holder.applyFilters(paidAndFree)

            assertEquals(3, repository.requests.size, "first load, the refused ask, the re-ask")
            val reasked = repository.requests.last()
            assertTrue(reasked.online, "the free choices still apply")
            assertEquals(setOf(LogisticsTag.PublicMeetFirst), reasked.logisticsTags)
            assertTrue(reasked.lifestyleTags.isEmpty(), "only the paid filter is cleared")
            val state = holder.state.value
            assertEquals(reasked, state.filters, "not reset to defaults")
            assertEquals(PAID_FILTER_PAYWALL_MESSAGE, state.paywallMessage, "the prompt stays after the re-ask lands")
            assertEquals(SubscriptionTier.SOCIAL, state.paywallUpgradeTier)
            assertNull(state.errorMessage)
            assertEquals(listOf("a"), state.cards.map { it.id })
            assertEquals(listOf<GridEvent>(GridEvent.OpenPaywall), events)
        }

    @Test
    fun aRestoredPaidFilterAfterAPlanLapsedIsStrippedWithoutPoppingThePaywall() =
        runTest {
            val store = InMemoryGridFilterStore()
            store.save(paidAndFree)
            val repository = RecordingGridFeedRepository(refuseAdvanced = true)
            val holder = GridStateHolder(repository, filterStore = store)
            val events = mutableListOf<GridEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.toList(events) }

            holder.primeFromCacheThenLoad()

            assertFalse(holder.state.value.filters.requiresAdvancedEntitlement)
            assertTrue(holder.state.value.filters.online)
            assertEquals(PAID_FILTER_PAYWALL_MESSAGE, holder.state.value.paywallMessage, "the row still says why")
            assertTrue(events.isEmpty(), "nobody tapped anything: no paywall sheet")
            assertFalse(store.saved!!.requiresAdvancedEntitlement, "the stripped set is what is remembered")
        }

    @Test
    fun aRefusalThatArrivesAfterANewerChoiceChangesNothing() =
        runTest {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val repository = RecordingGridFeedRepository(refuseAdvanced = true, gateRefusal = gate)
            val holder = GridStateHolder(repository)
            holder.load()

            val refused = async { holder.applyFilters(paidAndFree) }
            runCurrent()
            // The viewer changes their mind before the refusal lands.
            holder.applyFilters(GridFilters(verified = true))
            gate.complete(Unit)
            refused.await()

            assertEquals(GridFilters(verified = true), holder.state.value.filters, "the old refusal never writes its filters")
            assertNull(holder.state.value.paywallMessage)
            assertNull(holder.state.value.errorCode)
        }

    @Test
    fun aRefusalThatArrivesAfterSignOutNeverReachesTheNextAccount() =
        runTest {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val store = InMemoryGridFilterStore()
            val repository = RecordingGridFeedRepository(refuseAdvanced = true, gateRefusal = gate)
            val holder = GridStateHolder(repository, filterStore = store)
            holder.load()

            val refused = async { holder.applyFilters(paidAndFree) }
            runCurrent()
            holder.clearForSignOut()
            gate.complete(Unit)
            refused.await()

            assertEquals(GridFilters.defaults, holder.state.value.filters)
            assertNull(holder.state.value.paywallMessage)
            assertNull(store.saved)
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
    // When set, a refusal waits for it (so a test can act while the refused request is on the wire).
    private val gateRefusal: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
) : GridFeedRepository {
    val requests = mutableListOf<GridFilters>()

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        requests += filters
        if (refuseAdvanced && filters.requiresAdvancedEntitlement) {
            gateRefusal?.await()
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
