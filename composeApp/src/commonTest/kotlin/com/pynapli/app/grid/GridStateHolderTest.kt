package com.pynapli.app.grid

import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridStateHolderTest {
    @Test
    fun togglingOnlineSetsFlagAndCallsRepoWithCombinedFilters() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.toggleQuickFilter(QuickFilterId.Online)

            assertEquals(2, repository.loadCount)
            val latest = repository.lastFilters
            assertTrue(latest != null && latest.online)
            assertEquals(true, latest.toGridQueryRequest(cursor = null).onlineOnly)
        }

    @Test
    fun applyingIntentsSerializesToCanonicalTokens() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters.copy(
                    intents = setOf(Intent.Dating, Intent.OpenToCouples),
                ),
            )

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(setOf("dating", "open_to_couples"), request.intents.toSet())
        }

    @Test
    fun togglingTonightSetsTonightAvailableOnlyOnRequest() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.toggleQuickFilter(QuickFilterId.Tonight)

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertTrue(stateHolder.state.value.filters.tonightAvailable)
            assertEquals(true, request.tonightAvailableOnly)
        }

    @Test
    fun activeTodayFilterSerializesAndRoundTrips() {
        val request = GridFilters(activeToday = true).toGridQueryRequest(cursor = null)
        assertEquals(true, request.activeTodayOnly)
        assertEquals(true, GridFilters.fromGridQueryRequest(request).activeToday)
    }

    @Test
    fun freshAndTravelingHereFiltersSerializeAndRoundTrip() {
        val request = GridFilters(fresh = true, travelingHere = true).toGridQueryRequest(cursor = null)
        assertEquals(true, request.freshOnly)
        assertEquals(false, request.recentlyJoinedOnly, "Fresh replaced Recently joined")
        assertEquals(true, request.travelingHereOnly)
        val restored = GridFilters.fromGridQueryRequest(request)
        assertEquals(true, restored.fresh)
        assertEquals(true, restored.travelingHere)
        assertEquals(2, restored.activeCount)
    }

    @Test
    fun aPresetSavedWithRecentlyJoinedRestoresAsFresh() {
        val legacy =
            com.pynapli.shared.network.dto
                .GridQueryRequestDto(recentlyJoinedOnly = true)
        assertTrue(GridFilters.fromGridQueryRequest(legacy).fresh)
    }

    @Test
    fun togglingFreshSetsFreshOnlyOnRequest() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.toggleQuickFilter(QuickFilterId.Fresh)

            assertEquals(true, repository.lastFilters!!.toGridQueryRequest(cursor = null).freshOnly)
            assertEquals(1, stateHolder.state.value.filters.activeCount)
        }

    @Test
    fun hasPrivateAlbumFilterSerializesAndRoundTrips() {
        val request = GridFilters(hasPrivateAlbum = true).toGridQueryRequest(cursor = null)
        assertEquals(true, request.hasPrivateAlbumOnly)
        assertEquals(true, GridFilters.fromGridQueryRequest(request).hasPrivateAlbum)
    }

    @Test
    fun applyingLogisticsTagsSerializesToCanonicalTokens() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters.copy(
                    logisticsTags = setOf(LogisticsTag.Hosts, LogisticsTag.HotelFriendly),
                ),
            )

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(setOf("hosts", "hotel_friendly"), request.logisticsTags.toSet())
        }

    @Test
    fun applyingLifestyleTagsSerializesToCanonicalTokens() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters.copy(
                    lifestyleTags = setOf(LifestyleTag.SoftSwap, LifestyleTag.SameRoom),
                ),
            )

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(setOf("soft_swap", "same_room"), request.lifestyleTags.toSet())
        }

    @Test
    fun applyingDiscoveryStylesSerializesToServerValues() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters.copy(
                    discoveryStyles = setOf(DiscoveryStyle.Solo, DiscoveryStyle.Together),
                ),
            )

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(setOf("solo", "together"), request.discoveryStyles.toSet())
        }

    @Test
    fun applyingGenderAndOrientationSerializesToTokensAndRoundTrips() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters.copy(
                    genders = setOf("woman", "non_binary"),
                    orientations = setOf("pansexual"),
                ),
            )

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(setOf("woman", "non_binary"), request.genders.toSet())
            assertEquals(setOf("pansexual"), request.orientations.toSet())

            val roundTripped = GridFilters.fromGridQueryRequest(request)
            assertEquals(setOf("woman", "non_binary"), roundTripped.genders)
            assertEquals(setOf("pansexual"), roundTripped.orientations)
        }

    @Test
    fun applyingWithPhotosSerializesAndRoundTrips() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters
                    .copy(withPhotos = true),
            )

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(true, request.withPhotosOnly)
            assertEquals(true, GridFilters.fromGridQueryRequest(request).withPhotos)
        }

    @Test
    fun verifiedCouplesSetsTheRequestFieldCountsAsActiveAndClears() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters
                    .copy(verifiedCouples = true),
            )

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(true, request.verifiedCouplesOnly)
            // It does not switch on the separate "Linked" filter.
            assertEquals(null, request.linkedOnly)
            assertEquals(1, stateHolder.state.value.filters.activeCount)
            assertEquals(true, GridFilters.fromGridQueryRequest(request).verifiedCouples)

            stateHolder.clearAllFilters()
            assertEquals(false, stateHolder.state.value.filters.verifiedCouples)
            assertEquals(false, repository.lastFilters!!.toGridQueryRequest(cursor = null).verifiedCouplesOnly)
            assertEquals(0, stateHolder.state.value.filters.activeCount)
        }

    @Test
    fun togglingVerifiedSetsVerifiedOnlyOnRequest() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.toggleQuickFilter(QuickFilterId.Verified)

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertTrue(stateHolder.state.value.filters.verified)
            assertEquals(true, request.verifiedOnly)
            assertEquals(false, request.facePhotoVerifiedOnly)
            assertEquals(false, request.ageVerifiedOnly)
        }

    @Test
    fun togglingMultipleQuickFiltersCombinesIntoSingleRequest() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.toggleQuickFilter(QuickFilterId.Online)
            stateHolder.toggleQuickFilter(QuickFilterId.Linked)
            stateHolder.toggleQuickFilter(QuickFilterId.Singles)

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(true, request.onlineOnly)
            assertEquals(true, request.linkedOnly)
            assertEquals(listOf(GridFilters.SINGLES_CONTEXT), request.relationshipContexts)
        }

    @Test
    fun setRadiusMetersClampsAndReloads() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.setRadiusMeters(5_000)

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(5_000, request.radiusMeters)

            stateHolder.setRadiusMeters(meters = 999_999)
            assertEquals(GridFilters.MAX_RADIUS_METERS, repository.lastFilters!!.radiusMeters)

            stateHolder.setRadiusMeters(meters = 0)
            assertEquals(GridFilters.MIN_RADIUS_METERS, repository.lastFilters!!.radiusMeters)

            // An off-step radius is held as the server's step (26 km searches 50 km).
            stateHolder.setRadiusMeters(meters = 26_000)
            assertEquals(50_000, repository.lastFilters!!.radiusMeters)
        }

    @Test
    fun setAgeRangeNormalizesReversedBoundsAndReloads() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.setAgeRange(minimum = 50, maximum = 30)

            val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
            assertEquals(30, request.minimumAge)
            assertEquals(50, request.maximumAge)
        }

    @Test
    fun clearAllFiltersResetsToDefaultsAndReloads() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.toggleQuickFilter(QuickFilterId.Online)
            stateHolder.toggleQuickFilter(QuickFilterId.Linked)
            stateHolder.setRadiusMeters(2_000)
            val callsBefore = repository.loadCount

            stateHolder.clearAllFilters()

            assertEquals(GridFilters.defaults, stateHolder.state.value.filters)
            assertEquals(callsBefore + 1, repository.loadCount)
        }

    @Test
    fun applyFiltersWithSameValueIsNoOp() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            val before = repository.loadCount
            stateHolder.applyFilters(stateHolder.state.value.filters)

            assertEquals(before, repository.loadCount)
        }

    @Test
    fun refreshReFetchesCurrentFiltersAndPreservesThem() =
        runTest {
            val card =
                GridProfileCardUi(
                    id = "u1",
                    displayName = "One",
                    online = true,
                    distanceLabel = "1 km",
                    tone = GridCardTone.Warm,
                )
            val repository =
                FakeGridFeedRepository(
                    feeds =
                        listOf(
                            GridFeedUi(cards = listOf(card)),
                            GridFeedUi(cards = listOf(card)),
                            GridFeedUi(cards = listOf(card)),
                        ),
                )
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters
                    .copy(online = true),
            )
            val callsBeforeRefresh = repository.loadCount
            val filtersBeforeRefresh = stateHolder.state.value.filters

            stateHolder.refresh()

            assertEquals(callsBeforeRefresh + 1, repository.loadCount, "refresh must re-fetch")
            assertEquals(filtersBeforeRefresh, stateHolder.state.value.filters, "refresh must preserve filters")
            assertEquals(true, repository.lastFilters?.online, "refresh must use the current filters")
            assertFalse(stateHolder.state.value.isRefreshing, "refresh settles isRefreshing")
        }

    @Test
    fun quietLoadKeepsCardsVisibleAndNeverFlipsRefreshing() =
        runTest {
            val firstCard =
                GridProfileCardUi(
                    id = "first",
                    displayName = "First",
                    online = true,
                    distanceLabel = "1 km",
                    tone = GridCardTone.Warm,
                )
            val secondCard =
                GridProfileCardUi(
                    id = "second",
                    displayName = "Second",
                    online = false,
                    distanceLabel = "2 km",
                    tone = GridCardTone.Slate,
                )
            val gate = CompletableDeferred<Unit>()
            val repository =
                GatedGridFeedRepository(
                    feeds =
                        listOf(
                            GridFeedUi(cards = listOf(firstCard)),
                            GridFeedUi(cards = listOf(secondCard)),
                        ),
                    gateBeforeIndex = 1,
                    gate = gate,
                )
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()

            coroutineScope {
                val refreshingValues = mutableListOf<Boolean>()
                val cardIdsDuringQuiet = mutableListOf<List<String>>()
                val observerJob =
                    stateHolder.state
                        .onEach {
                            refreshingValues += it.isRefreshing
                            cardIdsDuringQuiet += it.cards.map { card -> card.id }
                        }.launchIn(this)

                val quietJob = async { stateHolder.load(quiet = true) }
                // Deterministic: run everything runnable on the test scheduler, which parks the load
                // on the gate. (A real-thread async + yield() could assert before the load started.)
                runCurrent()
                assertTrue(repository.parkedAtGate, "the load must be in flight before asserting on it")

                assertTrue(
                    refreshingValues.none { it },
                    "Quiet load must never flip isRefreshing to true while cards are visible. Saw: $refreshingValues",
                )
                assertTrue(
                    cardIdsDuringQuiet.all { it == listOf("first") },
                    "Existing cards must remain visible during a quiet load. Saw: $cardIdsDuringQuiet",
                )

                gate.complete(Unit)
                quietJob.await()
                observerJob.cancel()
            }

            val finalState = stateHolder.state.value
            assertEquals(listOf("second"), finalState.cards.map { it.id })
            assertFalse(finalState.isRefreshing)
            assertNull(finalState.errorMessage)
        }

    @Test
    fun quietLoadFailureKeepsExistingCardsAndDoesNotSurfaceError() =
        runTest {
            val initialCard =
                GridProfileCardUi(
                    id = "first",
                    displayName = "First",
                    online = true,
                    distanceLabel = "1 km",
                    tone = GridCardTone.Warm,
                )
            val repository =
                ScriptedGridFeedRepository(
                    outcomes =
                        listOf(
                            GridFeedOutcome.Success(GridFeedUi(cards = listOf(initialCard))),
                            GridFeedOutcome.Failure(RuntimeException("network down")),
                        ),
                )
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.load(quiet = true)

            val state = stateHolder.state.value
            assertEquals(listOf("first"), state.cards.map { it.id })
            assertNull(state.errorMessage)
            assertNull(state.errorCode)
            assertFalse(state.isRefreshing)
        }

    @Test
    fun ageVerificationRequiredRevertsFiltersAndSurfacesVerificationPrompt() =
        runTest {
            // GridService throws AGE_VERIFICATION_REQUIRED when an unverified viewer applies a
            // lifestyle filter. The client must revert to defaults (drop the gated filter), surface
            // the verification prompt instead of a raw error, and not show a paywall.
            val repository =
                ScriptedGridFeedRepository(
                    outcomes =
                        listOf(
                            GridFeedOutcome.Success(GridFeedUi(cards = emptyList())),
                            GridFeedOutcome.Failure(
                                GridFeedException(
                                    code = "AGE_VERIFICATION_REQUIRED",
                                    message = "Age verification is required for this feature.",
                                ),
                            ),
                        ),
                )
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(
                stateHolder.state.value.filters
                    .copy(lifestyleTags = setOf(LifestyleTag.SoftSwap)),
            )

            val state = stateHolder.state.value
            assertTrue(state.verificationRequired, "verificationRequired must be set")
            assertEquals("Verify your age to use lifestyle filters.", state.verificationMessage)
            assertEquals(GridFilters.defaults, state.filters, "gated filter change must be reverted")
            assertTrue(state.filters.lifestyleTags.isEmpty(), "lifestyle tag must be dropped")
            assertNull(state.paywallMessage, "age-verification must not surface a paywall")
            assertEquals("AGE_VERIFICATION_REQUIRED", state.errorCode)
            assertFalse(state.isRefreshing)
        }

    @Test
    fun successfulLoadClearsAnyPriorVerificationPrompt() =
        runTest {
            val card =
                GridProfileCardUi(
                    id = "u1",
                    displayName = "One",
                    online = true,
                    distanceLabel = "1 km",
                    tone = GridCardTone.Warm,
                )
            val repository =
                ScriptedGridFeedRepository(
                    outcomes =
                        listOf(
                            GridFeedOutcome.Failure(
                                GridFeedException(
                                    code = "AGE_VERIFICATION_REQUIRED",
                                    message = "Age verification is required for this feature.",
                                ),
                            ),
                            GridFeedOutcome.Success(GridFeedUi(cards = listOf(card))),
                        ),
                )
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            assertTrue(stateHolder.state.value.verificationRequired)

            stateHolder.refresh()

            val state = stateHolder.state.value
            assertFalse(state.verificationRequired, "a successful load clears the verification prompt")
            assertNull(state.verificationMessage)
            assertEquals(listOf("u1"), state.cards.map { it.id })
        }

    @Test
    fun nonQuietLoadFailurePreservesErrorSemantics() =
        runTest {
            val repository =
                ScriptedGridFeedRepository(
                    outcomes =
                        listOf(
                            GridFeedOutcome.Failure(RuntimeException("network down")),
                        ),
                )
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()

            val state = stateHolder.state.value
            assertTrue(state.cards.isEmpty())
            assertEquals("network down", state.errorMessage)
            assertFalse(state.isRefreshing)
        }

    @Test
    fun filterChangeKeepsCardsVisibleUntilNewFeedLands() =
        runTest {
            val firstCard =
                GridProfileCardUi(
                    id = "first",
                    displayName = "First",
                    online = true,
                    distanceLabel = "1 km",
                    tone = GridCardTone.Warm,
                )
            val secondCard =
                GridProfileCardUi(
                    id = "second",
                    displayName = "Second",
                    online = false,
                    distanceLabel = "2 km",
                    tone = GridCardTone.Slate,
                )
            val gate = CompletableDeferred<Unit>()
            val repository =
                GatedGridFeedRepository(
                    feeds =
                        listOf(
                            GridFeedUi(cards = listOf(firstCard)),
                            GridFeedUi(cards = listOf(secondCard)),
                        ),
                    gateBeforeIndex = 1,
                    gate = gate,
                )
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()

            coroutineScope {
                val cardIdsDuringSelect = mutableListOf<List<String>>()
                val observerJob =
                    stateHolder.state
                        .onEach { cardIdsDuringSelect += it.cards.map { card -> card.id } }
                        .launchIn(this)

                val selectJob = async { stateHolder.toggleQuickFilter(QuickFilterId.Online) }
                // Deterministic: run everything runnable on the test scheduler, which parks the load
                // on the gate. (A real-thread async + yield() could assert before the load started.)
                runCurrent()
                assertTrue(repository.parkedAtGate, "the load must be in flight before asserting on it")

                assertTrue(
                    cardIdsDuringSelect.all { it == listOf("first") },
                    "Filter change must not clear visible cards before the new feed lands. Saw: $cardIdsDuringSelect",
                )

                gate.complete(Unit)
                selectJob.await()
                observerJob.cancel()
            }

            val finalState = stateHolder.state.value
            assertEquals(listOf("second"), finalState.cards.map { it.id })
            assertTrue(finalState.filters.online)
            assertFalse(finalState.isRefreshing)
        }

    @Test
    fun presenceDeltaUpdatesOnlineFlagWithoutReorderingOrRefreshing() =
        runTest {
            val cards =
                listOf(
                    GridProfileCardUi(id = "a", displayName = "A", online = true, distanceLabel = null, tone = GridCardTone.Warm),
                    GridProfileCardUi(id = "b", displayName = "B", online = true, distanceLabel = null, tone = GridCardTone.Slate),
                    GridProfileCardUi(id = "c", displayName = "C", online = false, distanceLabel = null, tone = GridCardTone.Rust),
                )
            val repository = FakeGridFeedRepository(GridFeedUi(cards = cards))
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            assertEquals(1, repository.loadCount)

            stateHolder.applyPresenceDelta(
                PresenceDeltaDto(userId = "b", online = false, lastActiveAt = "2026-05-09T12:00:00Z"),
            )
            stateHolder.applyPresenceDelta(
                PresenceDeltaDto(userId = "c", online = true, lastActiveAt = "2026-05-09T12:00:01Z"),
            )

            val resulting = stateHolder.state.value.cards
            assertEquals(listOf("a", "b", "c"), resulting.map { it.id }, "Order must not change after presence deltas")
            assertEquals(true, resulting[0].online, "Card a online flag unchanged")
            assertEquals(false, resulting[1].online, "Card b flipped to offline")
            assertEquals(true, resulting[2].online, "Card c flipped to online")
            assertEquals(1, repository.loadCount, "Presence delta must not trigger a grid refresh")
        }

    @Test
    fun presenceDeltaForUnknownUserIsNoOp() =
        runTest {
            val cards =
                listOf(
                    GridProfileCardUi(id = "a", displayName = "A", online = true, distanceLabel = null, tone = GridCardTone.Warm),
                )
            val repository = FakeGridFeedRepository(GridFeedUi(cards = cards))
            val stateHolder = GridStateHolder(repository)
            stateHolder.load()

            val before = stateHolder.state.value
            stateHolder.applyPresenceDelta(
                PresenceDeltaDto(userId = "not-in-grid", online = false, lastActiveAt = "2026-05-09T12:00:00Z"),
            )
            val after = stateHolder.state.value
            assertEquals(before.cards, after.cards)
        }

    @Test
    fun loadMoreAppendsUniqueCardsAndAdvancesCursor() =
        runTest {
            val first =
                GridProfileCardUi(
                    id = "first",
                    displayName = "First",
                    online = true,
                    distanceLabel = "1 km",
                    tone = GridCardTone.Warm,
                )
            val duplicateFirst = first.copy(displayName = "First duplicate")
            val second =
                GridProfileCardUi(
                    id = "second",
                    displayName = "Second",
                    online = false,
                    distanceLabel = "2 km",
                    tone = GridCardTone.Slate,
                )
            val repository =
                FakeGridFeedRepository(
                    feeds =
                        listOf(
                            GridFeedUi(cards = listOf(first), nextCursor = "cursor-1"),
                            GridFeedUi(cards = listOf(duplicateFirst, second), nextCursor = "cursor-2"),
                        ),
                )
            val stateHolder = GridStateHolder(repository)

            stateHolder.load()
            stateHolder.loadMore()

            val state = stateHolder.state.value
            assertEquals(listOf("first", "second"), state.cards.map { it.id })
            assertEquals("cursor-2", state.nextCursor)
            assertEquals(listOf(null, "cursor-1"), repository.receivedCursors)
        }

    // Regression: a page requested under the old filters used to be appended after a reload,
    // mixing old-filter cards in and overwriting the fresh cursor with a stale one.
    @Test
    fun loadMorePageStartedBeforeReloadIsDropped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                CursorGatedGridFeedRepository(
                    firstPage = GridFeedUi(cards = listOf(card("a")), nextCursor = "old-cursor"),
                    reloadedPage = GridFeedUi(cards = listOf(card("b")), nextCursor = "fresh-cursor"),
                    stalePage = GridFeedUi(cards = listOf(card("stale")), nextCursor = "stale-cursor"),
                    gate = gate,
                )
            val stateHolder = GridStateHolder(repository)
            stateHolder.load()

            val pending = async { stateHolder.loadMore() }
            runCurrent() // loadMore is now parked on the gate with the old cursor
            stateHolder.load() // e.g. the user changed filters
            gate.complete(Unit)
            pending.await()

            val state = stateHolder.state.value
            assertEquals(listOf("b"), state.cards.map { it.id })
            assertEquals("fresh-cursor", state.nextCursor)
            assertFalse(state.isLoadingMore)
        }

    // Regression: cancelling an in-flight page (e.g. switching tabs) left isLoadingMore stuck true,
    // which blocked every later loadMore for the rest of the session.
    @Test
    fun cancelledLoadMoreReleasesTheLoadingFlag() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                CursorGatedGridFeedRepository(
                    firstPage = GridFeedUi(cards = listOf(card("a")), nextCursor = "cursor-1"),
                    reloadedPage = GridFeedUi(cards = listOf(card("a"))),
                    stalePage = GridFeedUi(cards = listOf(card("b"))),
                    gate = gate,
                )
            val stateHolder = GridStateHolder(repository)
            stateHolder.load()

            val pending = async { stateHolder.loadMore() }
            runCurrent()
            assertTrue(stateHolder.state.value.isLoadingMore)
            pending.cancel()
            pending.join()

            assertFalse(stateHolder.state.value.isLoadingMore)
        }

    @Test
    fun primeFromCacheRendersCachedCardsImmediatelyThenSwapsInFreshFeed() =
        runTest {
            val cached =
                GridProfileCardUi(
                    id = "cached",
                    displayName = "Cached",
                    online = false,
                    distanceLabel = "5 km",
                    tone = GridCardTone.Slate,
                )
            val fresh =
                GridProfileCardUi(
                    id = "fresh",
                    displayName = "Fresh",
                    online = true,
                    distanceLabel = "1 km",
                    tone = GridCardTone.Warm,
                )
            val gate = CompletableDeferred<Unit>()
            val repository =
                GatedGridFeedRepository(
                    feeds = listOf(GridFeedUi(cards = listOf(fresh))),
                    gateBeforeIndex = 0,
                    gate = gate,
                )
            val snapshotStore = FakeGridSnapshotStore(initial = listOf(cached))
            val stateHolder = GridStateHolder(repository, snapshotStore)

            val priming = async { stateHolder.primeFromCacheThenLoad() }
            // Run until the network load parks on the gate: the cached cards must already be visible
            // (instant paint) while it waits.
            runCurrent()
            assertTrue(repository.parkedAtGate, "the network load must be in flight")
            assertEquals(
                listOf("cached"),
                stateHolder.state.value.cards
                    .map { it.id },
            )

            gate.complete(Unit)
            priming.await()

            // The fresh feed atomically replaces the cached cards, and is persisted for next launch.
            assertEquals(
                listOf("fresh"),
                stateHolder.state.value.cards
                    .map { it.id },
            )
            assertEquals(listOf("fresh"), snapshotStore.saved.last().map { it.id })
            assertFalse(stateHolder.state.value.isRefreshing)
        }

    @Test
    fun primeFromCacheFallsBackToNormalLoadWhenNoSnapshot() =
        runTest {
            val fresh =
                GridProfileCardUi(
                    id = "fresh",
                    displayName = "Fresh",
                    online = true,
                    distanceLabel = "1 km",
                    tone = GridCardTone.Warm,
                )
            val repository = FakeGridFeedRepository(GridFeedUi(cards = listOf(fresh)))
            val snapshotStore = FakeGridSnapshotStore(initial = emptyList())
            val stateHolder = GridStateHolder(repository, snapshotStore)

            stateHolder.primeFromCacheThenLoad()

            assertEquals(
                listOf("fresh"),
                stateHolder.state.value.cards
                    .map { it.id },
            )
            assertEquals(1, repository.loadCount)
            assertEquals(listOf("fresh"), snapshotStore.saved.last().map { it.id })
        }

    // Section 0 repro B4 (onboarding + first grid plan), fixed in Section 1: the compose-time prime
    // joins the load already on the wire instead of superseding it.
    @Test
    fun theGridScreenReusesTheLoadTheLocationStepStarted() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                GatedGridFeedRepository(
                    feeds = listOf(GridFeedUi(cards = listOf(card("nearby")))),
                    gateBeforeIndex = 0,
                    gate = gate,
                )
            val stateHolder = GridStateHolder(repository, FakeGridSnapshotStore(initial = emptyList()))

            // Location step: the shared fix's submitAndRefresh starts the first grid load.
            val prefetch = async { stateHolder.load() }
            runCurrent()
            assertTrue(repository.parkedAtGate, "the Location step's load must be in flight")

            // The member reaches the grid before it answers: GridScreen composes and primes. That
            // used to start a second request and bump the generation, dropping the first answer.
            val priming = async { stateHolder.primeFromCacheThenLoad() }
            runCurrent()
            gate.complete(Unit)
            prefetch.await()
            priming.await()

            assertEquals(1, repository.loadCount, "one grid request for one first grid")
            assertEquals(
                listOf("nearby"),
                stateHolder.state.value.cards
                    .map { it.id },
            )
        }

    @Test
    fun aFilterChangeStillSupersedesTheLoadTheGridJoined() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                GatedGridFeedRepository(
                    feeds = listOf(GridFeedUi(cards = listOf(card("unfiltered"))), GridFeedUi(cards = listOf(card("online")))),
                    gateBeforeIndex = 0,
                    gate = gate,
                )
            val stateHolder = GridStateHolder(repository, FakeGridSnapshotStore(initial = emptyList()))

            val prefetch = async { stateHolder.load() }
            runCurrent()
            val priming = async { stateHolder.primeFromCacheThenLoad() }
            runCurrent()
            // The member taps Online before the first answer: the old request must lose.
            val filtered = async { stateHolder.toggleQuickFilter(QuickFilterId.Online) }
            runCurrent()
            gate.complete(Unit)
            prefetch.await()
            priming.await()
            filtered.await()

            assertEquals(2, repository.loadCount, "the prime starts no request of its own")
            assertEquals(
                listOf("online"),
                stateHolder.state.value.cards
                    .map { it.id },
            )
            assertTrue(stateHolder.state.value.filters.online)
            assertFalse(stateHolder.state.value.isRefreshing)
        }

    @Test
    fun theGridLoadsItselfWhenTheLoadItJoinedIsCancelled() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                GatedGridFeedRepository(
                    feeds = listOf(GridFeedUi(cards = listOf(card("nearby")))),
                    gateBeforeIndex = 0,
                    gate = gate,
                )
            val stateHolder = GridStateHolder(repository, FakeGridSnapshotStore(initial = emptyList()))

            val prefetch = async { stateHolder.load() }
            runCurrent()
            val priming = async { stateHolder.primeFromCacheThenLoad() }
            runCurrent()
            prefetch.cancel()
            runCurrent()
            gate.complete(Unit)
            priming.await()

            assertEquals(2, repository.loadCount)
            assertEquals(
                listOf("nearby"),
                stateHolder.state.value.cards
                    .map { it.id },
            )
            assertFalse(stateHolder.state.value.isRefreshing)
        }
}

class GridInstantLoadTest {
    private fun card(id: String) = GridProfileCardUi(id = id, displayName = id, online = false, distanceLabel = "1 km", tone = GridCardTone.Warm)

    @Test
    fun serverHasLocationFollowsLiveAnswersOnlyAndResetsOnSignOut() =
        runTest {
            val repository =
                ScriptedGridFeedRepository(
                    listOf(
                        GridFeedOutcome.Failure(GridFeedException(code = "LOCATION_REQUIRED", message = "Share your location.")),
                        GridFeedOutcome.Success(GridFeedUi(cards = listOf(card("a")))),
                        GridFeedOutcome.Failure(GridFeedException(code = "NETWORK_ERROR", message = "No connection.")),
                    ),
                )
            val stateHolder = GridStateHolder(repository, FakeGridSnapshotStore(initial = listOf(card("cached"))))
            assertNull(stateHolder.serverHasLocation.value, "cached cards say nothing about the server")

            stateHolder.load()
            assertEquals(false, stateHolder.serverHasLocation.value)
            stateHolder.load()
            assertEquals(true, stateHolder.serverHasLocation.value)
            stateHolder.load()
            assertEquals(true, stateHolder.serverHasLocation.value, "a network error changes nothing")

            stateHolder.clearForSignOut()
            assertNull(stateHolder.serverHasLocation.value)
        }

    @Test
    fun returningToTheGridTabKeepsFreshCardsWithoutARefetch() =
        runTest {
            val repository = FakeGridFeedRepository(GridFeedUi(cards = listOf(card("a"), card("b"))))
            val stateHolder = GridStateHolder(repository, FakeGridSnapshotStore())

            stateHolder.primeFromCacheThenLoad()
            stateHolder.rememberScroll(firstVisibleItemIndex = 7, firstVisibleItemScrollOffset = 42)
            // The screen leaves and re-enters composition (tab switch): no second load, no spinner.
            stateHolder.primeFromCacheThenLoad()

            assertEquals(1, repository.loadCount)
            assertFalse(stateHolder.state.value.isRefreshing)
            assertEquals(GridScrollAnchor(7, 42), stateHolder.scrollAnchor)
        }

    @Test
    fun aBlockedCardLeavesTheColdStartCacheToo() =
        runTest {
            val snapshotStore = FakeGridSnapshotStore()
            val stateHolder = GridStateHolder(FakeGridFeedRepository(GridFeedUi(cards = listOf(card("a"), card("b")))), snapshotStore)
            stateHolder.load()

            stateHolder.removeCardById("a")

            assertEquals(listOf("b"), snapshotStore.saved.last().map { it.id })
        }

    @Test
    fun signOutWipesTheGridAndItsCache() =
        runTest {
            val snapshotStore = FakeGridSnapshotStore()
            val stateHolder = GridStateHolder(FakeGridFeedRepository(GridFeedUi(cards = listOf(card("a")))), snapshotStore)
            stateHolder.load()
            stateHolder.rememberScroll(5, 10)

            stateHolder.clearForSignOut()

            assertTrue(
                stateHolder.state.value.cards
                    .isEmpty(),
            )
            assertEquals(1, snapshotStore.cleared)
            assertEquals(GridScrollAnchor(), stateHolder.scrollAnchor)
        }

    @Test
    fun theCachedSnapshotCarriesNoCoordinates() {
        val json =
            com.pynapli.shared.network.PynapliJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(GridProfileCardUi.serializer()),
                listOf(card("a").copy(distanceMeters = 1_000, linkedPartner = GridLinkedPartnerUi("p", "Partner", "https://x"))),
            )
        listOf("lat", "lon", "lng", "geom", "coordinate").forEach { key ->
            assertFalse(json.contains(key, ignoreCase = true), "snapshot must not hold $key: $json")
        }
    }
}

private class FakeGridSnapshotStore(
    private val initial: List<GridProfileCardUi> = emptyList(),
) : GridSnapshotStore {
    val saved = mutableListOf<List<GridProfileCardUi>>()

    override suspend fun load(): List<GridProfileCardUi> = initial

    override suspend fun save(cards: List<GridProfileCardUi>) {
        saved += cards
    }

    var cleared = 0
        private set

    override suspend fun clear() {
        cleared += 1
    }
}

private class FakeGridFeedRepository(
    private val feeds: List<GridFeedUi>,
) : GridFeedRepository {
    constructor(feed: GridFeedUi) : this(listOf(feed))

    var loadCount = 0
        private set
    val receivedCursors = mutableListOf<String?>()
    var lastFilters: GridFilters? = null
        private set

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        receivedCursors += cursor
        loadCount += 1
        lastFilters = filters
        return feeds.getOrElse(loadCount - 1) { feeds.last() }
    }
}

private class GatedGridFeedRepository(
    private val feeds: List<GridFeedUi>,
    private val gateBeforeIndex: Int,
    private val gate: CompletableDeferred<Unit>,
) : GridFeedRepository {
    var loadCount = 0
        private set

    /** True once a load is waiting on [gate]: lets a test prove it observed the in-flight state. */
    var parkedAtGate = false
        private set

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        val index = loadCount
        loadCount += 1
        if (index >= gateBeforeIndex) {
            parkedAtGate = true
            gate.await()
        }
        return feeds.getOrElse(index) { feeds.last() }
    }
}

private sealed class GridFeedOutcome {
    data class Success(
        val feed: GridFeedUi,
    ) : GridFeedOutcome()

    data class Failure(
        val error: Throwable,
    ) : GridFeedOutcome()
}

private class ScriptedGridFeedRepository(
    private val outcomes: List<GridFeedOutcome>,
) : GridFeedRepository {
    private var loadCount = 0

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        val outcome = outcomes.getOrElse(loadCount) { outcomes.last() }
        loadCount += 1
        return when (outcome) {
            is GridFeedOutcome.Success -> outcome.feed
            is GridFeedOutcome.Failure -> throw outcome.error
        }
    }
}

/** Pages requested with a cursor park on [gate]; cursor-less loads return [firstPage] then [reloadedPage]. */
private class CursorGatedGridFeedRepository(
    private val firstPage: GridFeedUi,
    private val reloadedPage: GridFeedUi,
    private val stalePage: GridFeedUi,
    private val gate: CompletableDeferred<Unit>,
) : GridFeedRepository {
    private var cursorlessLoads = 0

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        if (cursor != null) {
            gate.await()
            return stalePage
        }
        cursorlessLoads += 1
        return if (cursorlessLoads == 1) firstPage else reloadedPage
    }
}

private fun card(id: String) = GridProfileCardUi(id = id, displayName = id, online = false, distanceLabel = null, tone = GridCardTone.Slate)
