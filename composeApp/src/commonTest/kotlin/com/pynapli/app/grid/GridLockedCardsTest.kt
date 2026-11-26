package com.pynapli.app.grid

import com.pynapli.app.profile.FavoriteChange
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.PublicProfileRepository
import com.pynapli.app.profile.SamplePublicProfileRepository
import com.pynapli.app.store.StorePaywallFocus
import com.pynapli.app.store.storePaywallFocusForUnlockTier
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Section 3: locked cards past the viewer's plan limit (the server decides which are locked). */
class GridLockedCardsTest {
    @Test
    fun theLiveGridQueryOptsInButSavedPresetsAndStoredFiltersDoNot() {
        val filters = GridFilters(online = true)

        assertTrue(filters.toGridFeedRequest(cursor = "c1").acceptLockedCards)
        assertEquals("c1", filters.toGridFeedRequest(cursor = "c1").cursor)
        assertEquals(filters.toGridQueryRequest(cursor = "c1"), filters.toGridFeedRequest(cursor = "c1").copy(acceptLockedCards = false))
        assertFalse(filters.toGridQueryRequest(cursor = null).acceptLockedCards, "presets and the filter store keep the plain payload")

        // On the wire, with the client's own JSON settings.
        val json = PynapliJson.encodeToString(GridQueryRequestDto.serializer(), filters.toGridFeedRequest(cursor = null))
        assertTrue(json.contains("\"acceptLockedCards\":true"), json)
    }

    @Test
    fun aLockedServerResponseDecodesAndOldResponsesStillDo() {
        val body =
            """{"items":[{"userId":"u1","displayName":"Maya","online":true,"rankToken":"r1"},""" +
                """{"userId":"locked_x","displayName":"","online":false,"rankToken":"r2","locked":true}],""" +
                """"lockedFromIndex":1,"unlockTier":"social"}"""
        val feed = PynapliJson.decodeFromString(GridQueryResponseDto.serializer(), body).toGridFeedUi()
        assertEquals(listOf(false, true), feed.cards.map { it.locked })
        assertEquals(1, feed.lockedFromIndex)
        assertEquals(SubscriptionTier.SOCIAL, feed.unlockTier)

        val old = PynapliJson.decodeFromString(GridQueryResponseDto.serializer(), """{"items":[{"userId":"u1","displayName":"Maya","online":true,"rankToken":"r1"}]}""")
        assertNull(old.lockedFromIndex)
        assertFalse(old.items.single().locked)
    }

    @Test
    fun aLockedCardMapsWithoutIdentityAndTheBoundaryFieldsComeThrough() {
        val response =
            GridQueryResponseDto(
                items =
                    listOf(
                        dto("u1"),
                        dto(
                            "locked_abc",
                            locked = true,
                            displayName = "Should not show",
                            activeAgo = GridActiveAgo.HOUR_1,
                            partner = LinkedPartnerMiniDto(userId = "partner-real-id", displayName = "P"),
                        ),
                        // The prefix alone marks a card locked, even if the flag were ever missing.
                        dto("locked_def"),
                    ),
                lockedFromIndex = 1,
                unlockTier = SubscriptionTier.SOCIAL,
                resultLimitReached = true,
                resultLimitTier = SubscriptionTier.FREE,
            )

        val feed = response.toGridFeedUi()

        val locked = feed.cards[1]
        assertTrue(locked.locked)
        assertEquals("", locked.displayName)
        assertNull(locked.activeAgo)
        assertNull(locked.linkedPartner, "the partner mini carries a real id: never on a locked card")
        assertFalse(locked.coupleVerified)
        assertTrue(feed.cards[2].isLockedCard)
        assertFalse(feed.cards[0].isLockedCard)
        assertEquals(1, feed.lockedFromIndex)
        assertEquals(SubscriptionTier.SOCIAL, feed.unlockTier)
        assertNull(feed.resultLimitPaywallMessage, "the in-grid banner sells the rest, not the row above the Grid")
        assertNull(feed.resultLimitUpgradeTier)
    }

    @Test
    fun withoutLockedCardsTheResultLimitRowStaysAsTheFallback() {
        val feed = GridFeedUi(cards = listOf(card("a")), resultLimitReached = true, resultLimitTier = SubscriptionTier.FREE)

        assertEquals("Social unlocks more nearby profiles.", feed.resultLimitPaywallMessage)
        assertEquals(SubscriptionTier.SOCIAL, feed.resultLimitUpgradeTier)
    }

    @Test
    fun lockedCardsStayInTheListAndPagingContinuesThroughThem() =
        runTest {
            val repository =
                PagedFeeds(
                    first = GridFeedUi(cards = listOf(card("a"), card("b")), nextCursor = "p2"),
                    pages =
                        mapOf(
                            "p2" to lockedPage(listOf(card("c"), locked("locked_1"), locked("locked_2")), next = "p3"),
                            "p3" to lockedPage(listOf(locked("locked_3")), next = "p4"),
                            // The final page past the cap: nothing on it, and the server reports the cap.
                            "p4" to GridFeedUi(cards = emptyList(), resultLimitReached = true, resultLimitTier = SubscriptionTier.FREE),
                        ),
                )
            val holder = GridStateHolder(repository)
            holder.load()
            holder.loadMore()
            holder.loadMore()
            holder.loadMore()

            val state = holder.state.value
            assertEquals(listOf("a", "b", "c", "locked_1", "locked_2", "locked_3"), state.cards.map { it.id })
            assertEquals(SubscriptionTier.SOCIAL, state.unlockTier)
            assertNull(state.nextCursor)
            assertNull(state.paywallMessage, "a late page reporting the cap does not bring the row back")
            assertNull(state.paywallUpgradeTier)
        }

    @Test
    fun lockedCardsNeverSubscribeToPresence() =
        runTest {
            val holder = GridStateHolder(PagedFeeds(first = lockedPage(listOf(card("a"), locked("locked_1"), card("b")), next = null)))
            holder.load()

            assertEquals(listOf("a", "b"), holder.visibleUserIds.first().toList())
        }

    @Test
    fun theColdStartSnapshotNeverHoldsLockedCards() =
        runTest {
            val snapshots = RecordingSnapshotStore(cached = listOf(card("old"), locked("locked_old")))
            val repository = PagedFeeds(first = lockedPage(listOf(card("a"), locked("locked_1")), next = null))
            val holder = GridStateHolder(repository, snapshotStore = snapshots)

            holder.primeFromCacheThenLoad()

            assertEquals(listOf("a"), snapshots.saved.last().map { it.id })
            assertTrue(snapshots.saved.all { saved -> saved.none { it.isLockedCard } })
        }

    @Test
    fun aCachedLockedCardIsNeverPainted() =
        runTest {
            val snapshots = RecordingSnapshotStore(cached = listOf(card("old"), locked("locked_old")))
            // The live answer never comes back in this test: only the cache paints.
            val holder = GridStateHolder(NeverAnswers, snapshotStore = snapshots)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.primeFromCacheThenLoad() }

            assertEquals(
                listOf("old"),
                holder.state.value.cards
                    .map { it.id },
            )
        }

    @Test
    fun newNearbyCountsOnlyUnlockedPeople() =
        runTest {
            val repository = PagedFeeds(first = GridFeedUi(cards = listOf(card("a"), card("b"))))
            val holder = GridStateHolder(repository)
            holder.load()

            repository.first = lockedPage(listOf(card("a"), card("new"), locked("locked_1"), locked("locked_2")), next = null)
            holder.refreshLive()
            assertEquals(1, holder.state.value.newNearbyCount, "only the new person, not the locks")

            repository.first = lockedPage(listOf(card("a"), card("b"), locked("locked_9")), next = null)
            holder.refreshLive()
            assertEquals(0, holder.state.value.newNearbyCount, "a new lock alone raises no pill")
        }

    @Test
    fun theBannerSitsBeforeTheFirstLockedCardEvenAfterARemoval() =
        runTest {
            val holder = GridStateHolder(PagedFeeds(first = lockedPage(listOf(card("a"), card("b"), locked("locked_1"), locked("locked_2")), next = null)))
            holder.load()
            assertEquals(2, holder.state.value.lockedBannerIndex)

            // A block before the boundary: the server's lockedFromIndex (2) is now one off; the list is not.
            holder.removeCardById("a")
            assertEquals(1, holder.state.value.lockedBannerIndex)

            assertNull(GridUiState(cards = listOf(card("a"))).lockedBannerIndex, "no locks, no banner")
        }

    @Test
    fun gridItemsAfterTheBannerCountAsTheCardBefore() {
        assertEquals(5, cardIndexForGridItem(5, bannerAt = null))
        assertEquals(1, cardIndexForGridItem(1, bannerAt = 2))
        assertEquals(2, cardIndexForGridItem(2, bannerAt = 2), "the banner counts as the card it precedes")
        assertEquals(2, cardIndexForGridItem(3, bannerAt = 2))
    }

    @Test
    fun aLockedTileOpensThePaywallNeverAProfile() {
        val tap = routeGridTileTap(locked("locked_1"), unlockTier = SubscriptionTier.SELECT)
        assertEquals(GridTileTap.OpenPaywall(SubscriptionTier.SELECT), tap)

        val open = assertIs<GridTileTap.OpenProfile>(routeGridTileTap(card("a"), unlockTier = SubscriptionTier.SELECT))
        assertEquals("a", open.card.id)

        assertEquals(StorePaywallFocus.Plans, storePaywallFocusForUnlockTier(SubscriptionTier.SOCIAL))
        assertEquals(StorePaywallFocus.Select, storePaywallFocusForUnlockTier(SubscriptionTier.SELECT))
        assertEquals(StorePaywallFocus.Plans, storePaywallFocusForUnlockTier(null))
        assertEquals("See everyone nearby with Social", lockedBannerMessage(SubscriptionTier.SOCIAL))
        assertEquals("See everyone nearby with Select", lockedBannerMessage(SubscriptionTier.SELECT))
    }

    @Test
    fun aLockedCardIsNeverFetchedOrSelected() =
        runTest {
            val requested = mutableListOf<String>()
            val sample = SamplePublicProfileRepository()
            val repository =
                object : PublicProfileRepository by sample {
                    override suspend fun loadProfile(userId: String): AppResult<PublicProfileDto> {
                        requested += userId
                        return sample.loadProfile(userId)
                    }
                }
            val holder = ProfileDetailStateHolder(repository)

            holder.open(locked("locked_1"))

            assertTrue(requested.isEmpty(), "no profile request for a locked token")
            assertNull(holder.state.value.selectedCard, "nothing to block, hide, favourite or message")
        }

    @Test
    fun aLockedTokenNeverJoinsFavorites() =
        runTest {
            // The Favorites mapping drops a locked card the server should never have sent.
            assertEquals(listOf("u1"), listOf(dto("u1"), dto("locked_1", locked = true), dto("locked_2")).toFavoriteCards().map { it.id })

            val favorites = FavoritesStateHolder(FixedFavorites(GridFeedUi(cards = listOf(card("a")))))
            favorites.show()

            favorites.onFavoriteChanged(FavoriteChange("locked_2", favorite = true))
            assertFalse(favorites.state.value.stale, "a locked token is not a star to refetch for")
        }

    @Test
    fun aPlanChangeReloadsTheGridAndScrollsToTheTop() =
        runTest {
            val repository = PagedFeeds(first = lockedPage(listOf(card("a"), locked("locked_1")), next = null))
            val holder = GridStateHolder(repository)
            val events = mutableListOf<GridEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.toList(events) }
            holder.setOnScreen(true)
            holder.load()
            assertEquals(1, repository.firstPageRequests)

            holder.onPlanTierObserved(null)
            holder.onPlanTierObserved(SubscriptionTier.FREE)
            assertEquals(1, repository.firstPageRequests, "the first plan seen is a baseline, not a change")
            holder.onPlanTierObserved(SubscriptionTier.FREE)
            assertEquals(1, repository.firstPageRequests, "the same plan again changes nothing")

            // Bought Social: the server now answers with everyone unlocked.
            repository.first = GridFeedUi(cards = listOf(card("a"), card("b")))
            holder.onPlanTierObserved(SubscriptionTier.SOCIAL)
            assertEquals(2, repository.firstPageRequests)
            assertEquals(
                listOf("a", "b"),
                holder.state.value.cards
                    .map { it.id },
            )
            assertNull(holder.state.value.lockedBannerIndex)
            assertEquals(listOf<GridEvent>(GridEvent.ScrollToTop), events)

            // A lapse picked up off screen: nothing now, the next return reloads from the top.
            holder.setOnScreen(false)
            holder.rememberScroll(firstVisibleItemIndex = 40, firstVisibleItemScrollOffset = 10)
            holder.onPlanTierObserved(SubscriptionTier.FREE)
            assertEquals(2, repository.firstPageRequests)
            assertTrue(holder.resultsOlderThan(GridStateHolder.RESULTS_EXPIRE_AFTER), "marked expired for the return")
            assertEquals(GridScrollAnchor(), holder.scrollAnchor)
        }

    @Test
    fun signOutResetsThePlanBaseline() =
        runTest {
            val repository = PagedFeeds(first = GridFeedUi(cards = listOf(card("a"))))
            val holder = GridStateHolder(repository)
            holder.setOnScreen(true)
            holder.load()
            holder.onPlanTierObserved(SubscriptionTier.SOCIAL)
            holder.clearForSignOut()
            holder.load()
            val before = repository.firstPageRequests

            // The next account's plan differs from the last one's: still only a baseline.
            holder.onPlanTierObserved(SubscriptionTier.FREE)
            assertEquals(before, repository.firstPageRequests)
        }

    private fun card(id: String): GridProfileCardUi = GridProfileCardUi(id = id, displayName = "User $id", online = true, distanceLabel = "1 km")

    private fun locked(id: String): GridProfileCardUi = GridProfileCardUi(id = id, displayName = "", online = true, distanceLabel = "2 km", locked = true)

    private fun lockedPage(
        cards: List<GridProfileCardUi>,
        next: String?,
    ): GridFeedUi =
        GridFeedUi(
            cards = cards,
            nextCursor = next,
            lockedFromIndex = cards.indexOfFirst { it.isLockedCard }.takeIf { it >= 0 },
            unlockTier = SubscriptionTier.SOCIAL.takeIf { cards.any { it.isLockedCard } },
        )

    private fun dto(
        id: String,
        locked: Boolean = false,
        displayName: String = "User $id",
        activeAgo: String? = null,
        partner: LinkedPartnerMiniDto? = null,
    ): GridCardDto =
        GridCardDto(
            userId = id,
            displayName = displayName,
            online = true,
            rankToken = "r-$id",
            linkedPartner = partner,
            coupleVerified = partner != null,
            activeAgo = activeAgo,
            locked = locked,
        )
}

/** First page from [first] (swappable), later pages by cursor. */
private class PagedFeeds(
    var first: GridFeedUi,
    private val pages: Map<String, GridFeedUi> = emptyMap(),
) : GridFeedRepository {
    var firstPageRequests = 0

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        if (cursor == null) {
            firstPageRequests += 1
            return first
        }
        return pages.getValue(cursor)
    }
}

private object NeverAnswers : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi = kotlinx.coroutines.awaitCancellation()
}

private class RecordingSnapshotStore(
    private val cached: List<GridProfileCardUi>,
) : GridSnapshotStore {
    val saved = mutableListOf<List<GridProfileCardUi>>()

    override suspend fun load(): List<GridProfileCardUi> = cached

    override suspend fun save(cards: List<GridProfileCardUi>) {
        saved += cards
    }

    override suspend fun clear() = Unit
}

private class FixedFavorites(
    private val feed: GridFeedUi,
) : FavoritesRepository {
    override suspend fun loadFavorites(cursor: String?): GridFeedUi = feed
}
