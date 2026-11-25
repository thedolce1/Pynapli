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
    fun aStoredBlobRoundTripsForItsOwnerOnlyAndACorruptOneReadsAsNothing() {
        val filters = GridFilters(online = true, fresh = true, radiusMeters = 10_000, minimumAge = 30, maximumAge = 45)
        val blob = encodeGridFilters("member-a", filters)
        assertEquals(filters, decodeGridFilters(blob, "member-a"))
        assertNull(decodeGridFilters(blob, "member-b"), "another account never gets them")
        assertNull(decodeGridFilters(blob, null), "nor does nobody")
        assertNull(decodeGridFilters("{not json", "member-a"))
        assertNull(decodeGridFilters(null, "member-a"))
        // A bare preset-shaped blob from an earlier build carries no owner: never restored.
        assertNull(decodeGridFilters("""{"onlineOnly":true}""", "member-a"))
        assertFalse(blob.contains("cursor\":\""), "no cursor is stored")
    }

    @Test
    fun accountBoundStoreDeletesWhatAnotherAccountChoseAndKeepsNothingSignedOut() =
        runTest {
            var blob: String? = null
            var userId: String? = "member-a"
            val store =
                AccountBoundGridFilterStore(
                    currentUserId = { userId },
                    read = { blob },
                    write = { blob = it },
                    remove = { blob = null },
                )
            store.save(paidAndFree)
            assertEquals(paidAndFree, store.load())

            // Another tab switched this tab's session to B without a sign-out passing through here.
            userId = "member-b"
            assertNull(store.load(), "A's (lifestyle) choices never reach B")
            assertNull(blob, "and are deleted, not just hidden")

            userId = null
            store.save(paidAndFree)
            assertNull(blob, "a save with nobody signed in keeps nothing")
        }

    @Test
    fun aCrossTabSwitchNeverRestoresTheOtherAccountsFiltersIntoTheGrid() =
        runTest {
            var blob: String? = null
            var userId: String? = "member-a"
            val store = AccountBoundGridFilterStore({ userId }, { blob }, { blob = it }, { blob = null })
            store.save(paidAndFree)

            userId = "member-b"
            val repository = RecordingGridFeedRepository()
            val holder = GridStateHolder(repository, filterStore = store)
            holder.primeFromCacheThenLoad()

            assertEquals(GridFilters.defaults, repository.requests.single(), "B's first query carries none of A's filters")
        }

    @Test
    fun aWriteUnderWayAtSignOutIsClearedAfterAndALaterOneNeverLands() =
        runTest {
            val store = GatedGridFilterStore()
            val holder = GridStateHolder(RecordingGridFeedRepository(), filterStore = store)
            holder.load()
            store.saveGate = kotlinx.coroutines.CompletableDeferred()

            // A's choice is being written when A signs out.
            val choosing = async { holder.toggleQuickFilter(QuickFilterId.Verified) }
            runCurrent()
            val signingOut = async { holder.clearForSignOut() }
            runCurrent()
            store.saveGate!!.complete(Unit)
            choosing.await()
            signingOut.await()

            assertNull(store.saved, "the sign-out clear lands after the in-flight write")
        }

    @Test
    fun aReadUnderWayAtSignOutNeverAppliesThePreviousAccountsFilters() =
        runTest {
            val store = GatedGridFilterStore(initial = paidAndFree)
            store.loadGate = kotlinx.coroutines.CompletableDeferred()
            val repository = RecordingGridFeedRepository()
            val holder = GridStateHolder(repository, filterStore = store)

            val loading = async { holder.load() }
            runCurrent()
            val signingOut = async { holder.clearForSignOut() }
            runCurrent()
            store.loadGate!!.complete(Unit)
            loading.await()
            signingOut.await()

            assertEquals(GridFilters.defaults, holder.state.value.filters)
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

/** A store whose load or save can be held on a gate, to put a sign-out in the middle of it. */
private class GatedGridFilterStore(
    initial: GridFilters? = null,
) : GridFilterStore {
    var saved: GridFilters? = initial
        private set
    var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var saveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun load(): GridFilters? {
        val value = saved
        loadGate?.await()
        return value
    }

    override suspend fun save(filters: GridFilters) {
        saveGate?.await()
        saved = filters
    }

    override suspend fun clear() {
        saved = null
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
