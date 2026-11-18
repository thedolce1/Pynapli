package com.pynapli.app.grid

import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Grid is kept across tabs, so changes made elsewhere must still reach it. */
class GridCrossTabTest {
    private fun card(id: String) = GridProfileCardUi(id = id, displayName = id, online = false, distanceLabel = "1 km", tone = GridCardTone.Warm)

    @Test
    fun anInvalidatedGridRefetchesQuietlyOnReturn() =
        runTest {
            val repository = CountingRepository { GridFeedUi(listOf(card("a"))) }
            val holder = GridStateHolder(repository)
            holder.primeFromCacheThenLoad()

            // Travel switched on from Swipe while the Grid was away.
            holder.invalidate()
            holder.primeFromCacheThenLoad()

            assertEquals(2, repository.calls)
        }

    @Test
    fun aBlockFromAnySheetIsAnnouncedToListsKeptAcrossTabs() =
        runTest {
            val profile = ProfileDetailStateHolder(SamplePublicProfileRepository())
            profile.open(card("blocked"))
            val removed = async { profile.removedProfiles.first() }
            runCurrent()

            profile.blockUser()

            assertEquals("blocked", removed.await())
        }

    @Test
    fun aCachedGridPaintsWithoutASkeletonFirst() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                CountingRepository {
                    gate.await()
                    GridFeedUi(listOf(card("fresh")))
                }
            val holder = GridStateHolder(repository, StaticSnapshotStore(listOf(card("cached"))))
            assertFalse(holder.state.value.cacheChecked, "nothing is drawn until the cache has been read")
            val skeletonStates = mutableListOf<GridUiState>()
            val watcher =
                holder.state
                    .onEach { state -> if (state.cacheChecked && state.cards.isEmpty() && state.isRefreshing) skeletonStates += state }
                    .launchIn(this)

            val priming = launch { holder.primeFromCacheThenLoad() }
            runCurrent()
            assertEquals(
                listOf("cached"),
                holder.state.value.cards
                    .map { it.id },
            )
            gate.complete(Unit)
            priming.join()
            runCurrent()

            assertTrue(skeletonStates.isEmpty(), "a skeleton state was emitted: $skeletonStates")
            assertEquals(
                listOf("fresh"),
                holder.state.value.cards
                    .map { it.id },
            )
            watcher.cancel()
        }

    @Test
    fun aLoadThatLandsAfterSignOutNeverBringsTheOldCardsBack() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                CountingRepository {
                    gate.await()
                    GridFeedUi(listOf(card("old-account")))
                }
            val holder = GridStateHolder(repository)
            val loading = launch { holder.load() }
            runCurrent()

            holder.clearForSignOut()
            gate.complete(Unit)
            loading.join()

            assertTrue(
                holder.state.value.cards
                    .isEmpty(),
            )
        }
}

private class CountingRepository(
    private val feed: suspend () -> GridFeedUi,
) : GridFeedRepository {
    var calls = 0

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        calls += 1
        return feed()
    }
}

private class StaticSnapshotStore(
    private val cards: List<GridProfileCardUi>,
) : GridSnapshotStore {
    override suspend fun load(): List<GridProfileCardUi> = cards

    override suspend fun save(cards: List<GridProfileCardUi>) = Unit

    override suspend fun clear() = Unit
}
