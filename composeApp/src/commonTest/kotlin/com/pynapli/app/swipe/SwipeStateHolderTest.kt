package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwipeStateHolderTest {
    @Test
    fun loadLocksPaidFiltersWhenDeckIsAdSupported() =
        runBlocking {
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = emptyList(), adSupported = true),
                )
            val stateHolder = SwipeStateHolder(repository)

            stateHolder.load()

            val filters = stateHolder.state.value.filters
            assertFalse(filters.first { it.type == SwipeFilterType.ForYou }.locked)
            assertTrue(filters.first { it.type == SwipeFilterType.Linked }.locked)
            assertTrue(filters.first { it.type == SwipeFilterType.Age }.locked)
            assertTrue(stateHolder.state.value.adSupported)
        }

    @Test
    fun introNoteValidationStopsOverLimitRequest() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), adSupported = false),
                )
            val stateHolder = SwipeStateHolder(repository)

            stateHolder.load()
            stateHolder.submitIntroNote(card, "x".repeat(141))

            val state = stateHolder.state.value
            assertEquals(0, repository.submitCount)
            assertEquals("Intro note must be 140 characters or less.", state.errorMessage)
            assertEquals(card, state.topCard)
        }

    @Test
    fun recoverableFailureKeepsSwipeQueuedForRetry() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-1"),
                    actionResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline", recoverable = true)),
                )
            val queueStore = FakeSwipeActionQueueStore()
            val stateHolder =
                SwipeStateHolder(
                    repository = repository,
                    actionIdFactory = { "action-1" },
                    queueStore = queueStore,
                )

            stateHolder.load()
            stateHolder.submitAction(card, SwipeAction.LIKE)

            assertEquals(1, repository.submitCount)
            assertEquals("action-1", repository.submittedActions.single().actionId)
            assertEquals("deck-1", repository.submittedActions.single().deckSessionId)
            assertEquals(1, stateHolder.state.value.pendingActionCount)
            assertEquals("action-1", queueStore.savedActions().single().actionId)
            assertNull(stateHolder.state.value.topCard)
            assertEquals("Swipe saved. We will retry when the connection is back.", stateHolder.state.value.errorMessage)

            repository.actionResult = AppResult.Success(null)
            stateHolder.retryPendingActions()

            assertEquals(2, repository.submitCount)
            assertEquals("action-1", repository.submittedActions.last().actionId)
            assertEquals(0, stateHolder.state.value.pendingActionCount)
            assertEquals(emptyList(), queueStore.savedActions())
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun nonRecoverableFailureRestoresCardAndClearsQueue() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-1"),
                    actionResult = AppResult.Failure(AppError("INVALID_SWIPE_CARD_TOKEN", "Card expired.", recoverable = false)),
                )
            val stateHolder = SwipeStateHolder(repository, actionIdFactory = { "action-1" })

            stateHolder.load()
            stateHolder.submitAction(card, SwipeAction.PASS)

            val state = stateHolder.state.value
            assertEquals(1, repository.submitCount)
            assertEquals(0, state.pendingActionCount)
            assertEquals(card, state.topCard)
            assertEquals("Card expired.", state.errorMessage)
        }

    @Test
    fun queueFullStopsAdditionalOptimisticActions() =
        runBlocking {
            val cards = (1..11).map { index -> swipeCard(userId = "target-$index") }
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = cards, deckSessionId = "deck-1"),
                    actionResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline", recoverable = true)),
                )
            var actionIndex = 0
            val stateHolder = SwipeStateHolder(repository, actionIdFactory = { "action-${++actionIndex}" })

            stateHolder.load()
            repeat(10) {
                stateHolder.submitAction(stateHolder.state.value.topCard ?: error("expected top card"), SwipeAction.LIKE)
            }
            stateHolder.submitAction(stateHolder.state.value.topCard ?: error("expected top card"), SwipeAction.LIKE)

            val state = stateHolder.state.value
            assertEquals(10, state.pendingActionCount)
            assertEquals("SWIPE_ACTION_QUEUE_FULL", state.errorCode)
            assertEquals(cards[10], state.topCard)
            assertEquals(10, actionIndex)
        }

    @Test
    fun loadRestoresPersistedQueueAndRetriesWithStableActionId() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val queued =
                QueuedSwipeAction(
                    actionId = "action-restored",
                    deckSessionId = "deck-old",
                    card = card,
                    action = SwipeAction.LIKE,
                )
            val queueStore =
                FakeSwipeActionQueueStore(
                    initialActions = mapOf("viewer-1" to listOf(queued)),
                )
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-new"),
                )
            val stateHolder =
                SwipeStateHolder(
                    repository = repository,
                    queueStore = queueStore,
                )

            stateHolder.load(ownerUserId = "viewer-1")

            val state = stateHolder.state.value
            assertEquals(1, repository.submitCount)
            assertEquals("action-restored", repository.submittedActions.single().actionId)
            assertEquals("deck-old", repository.submittedActions.single().deckSessionId)
            assertEquals(0, state.pendingActionCount)
            assertNull(state.topCard)
            assertEquals(emptyList(), queueStore.savedActions("viewer-1"))
        }

    @Test
    fun persistedQueueIsScopedByViewer() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val queueStore =
                FakeSwipeActionQueueStore(
                    initialActions =
                        mapOf(
                            "viewer-1" to
                                listOf(
                                    QueuedSwipeAction(
                                        actionId = "action-other-viewer",
                                        deckSessionId = "deck-old",
                                        card = card,
                                        action = SwipeAction.LIKE,
                                    ),
                                ),
                        ),
                )
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-new"),
                )
            val stateHolder =
                SwipeStateHolder(
                    repository = repository,
                    queueStore = queueStore,
                )

            stateHolder.load(ownerUserId = "viewer-2")

            assertEquals(0, repository.submitCount)
            assertEquals(0, stateHolder.state.value.pendingActionCount)
            assertEquals(card, stateHolder.state.value.topCard)
            assertEquals("action-other-viewer", queueStore.savedActions("viewer-1").single().actionId)
        }

    @Test
    fun internalReloadKeepsKnownViewerQueueScope() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-1"),
                    actionResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline", recoverable = true)),
                )
            val queueStore = FakeSwipeActionQueueStore()
            val stateHolder =
                SwipeStateHolder(
                    repository = repository,
                    actionIdFactory = { "action-viewer-scoped" },
                    queueStore = queueStore,
                )

            stateHolder.load(ownerUserId = "viewer-1")
            stateHolder.selectFilter(SwipeFilterType.Linked)
            stateHolder.submitAction(stateHolder.state.value.topCard!!, SwipeAction.LIKE)

            assertEquals("action-viewer-scoped", queueStore.savedActions("viewer-1").single().actionId)
            assertEquals(emptyList(), queueStore.savedActions())
        }

    @Test
    fun anonymousPendingQueueMigratesWhenViewerIdBecomesAvailable() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val queued =
                QueuedSwipeAction(
                    actionId = "action-anonymous",
                    deckSessionId = "deck-old",
                    card = card,
                    action = SwipeAction.LIKE,
                )
            val queueStore =
                FakeSwipeActionQueueStore(
                    initialActions = mapOf("anonymous" to listOf(queued)),
                )
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-new"),
                    actionResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline", recoverable = true)),
                )
            val stateHolder =
                SwipeStateHolder(
                    repository = repository,
                    queueStore = queueStore,
                )

            stateHolder.load(ownerUserId = "viewer-1")

            assertEquals(1, repository.submitCount)
            assertEquals("action-anonymous", repository.submittedActions.single().actionId)
            assertEquals("action-anonymous", queueStore.savedActions("viewer-1").single().actionId)
            assertEquals(emptyList(), queueStore.savedActions())
            assertEquals(1, stateHolder.state.value.pendingActionCount)
        }

    @Test
    fun rewindRestoresRecoverableQueuedActionWithoutServerCall() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-1"),
                    actionResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline", recoverable = true)),
                )
            val queueStore = FakeSwipeActionQueueStore()
            val stateHolder =
                SwipeStateHolder(
                    repository = repository,
                    actionIdFactory = { "action-1" },
                    queueStore = queueStore,
                )

            stateHolder.load()
            stateHolder.submitAction(card, SwipeAction.LIKE)
            stateHolder.rewind()

            val state = stateHolder.state.value
            assertEquals(1, repository.submitCount)
            assertEquals(0, repository.rewindCount)
            assertEquals(0, state.pendingActionCount)
            assertEquals(emptyList(), queueStore.savedActions())
            assertEquals(card, state.topCard)
            assertNull(state.errorMessage)
        }

    @Test
    fun rewindPrependsServerRestoredCardAndDeckSession() =
        runBlocking {
            val first = swipeCard(userId = "target-1")
            val restored = swipeCard(userId = "target-2").copy(rankToken = "rank-restored")
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(first), deckSessionId = "deck-1"),
                    rewindResult =
                        AppResult.Success(
                            SwipeRewindUi(
                                deckSessionId = "deck-restored",
                                card = restored,
                            ),
                        ),
                )
            val stateHolder = SwipeStateHolder(repository)

            stateHolder.load()
            stateHolder.rewind()

            val state = stateHolder.state.value
            assertEquals(1, repository.rewindCount)
            assertEquals("deck-restored", state.deckSessionId)
            assertEquals(restored, state.topCard)
            assertNull(state.errorMessage)
        }

    @Test
    fun loadStoresNextCursorAndAutoLoadsAdditionalPage() =
        runBlocking {
            val firstPage = (1..3).map { swipeCard(userId = "p1-$it") }
            val secondPage = (1..3).map { swipeCard(userId = "p2-$it") }
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = firstPage, deckSessionId = "deck-1", nextCursor = "cursor-1"),
                    pagesByCursor =
                        mapOf(
                            "cursor-1" to SwipeDeckUi(cards = secondPage, deckSessionId = "deck-1", nextCursor = null),
                        ),
                )
            val stateHolder = SwipeStateHolder(repository)

            stateHolder.load()

            val afterInitialLoad = stateHolder.state.value
            assertEquals("cursor-1", afterInitialLoad.nextCursor)
            assertEquals(3, afterInitialLoad.cards.size)
            assertTrue(afterInitialLoad.hasMore)
            assertEquals(1, repository.deckLoadCount)
            assertEquals(listOf<String?>(null), repository.deckCursors)

            stateHolder.submitAction(stateHolder.state.value.topCard ?: error("expected top card"), SwipeAction.PASS)
            stateHolder.loadMoreIfNeeded()

            val afterPaging = stateHolder.state.value
            assertEquals(2, repository.deckLoadCount)
            assertEquals(listOf<String?>(null, "cursor-1"), repository.deckCursors)
            assertEquals(listOf("p1-2", "p1-3", "p2-1", "p2-2", "p2-3"), afterPaging.cards.map { it.userId })
            assertNull(afterPaging.nextCursor)
            assertFalse(afterPaging.isPaging)
        }

    @Test
    fun loadMoreSkipsWhenAboveThresholdOrNoCursor() =
        runBlocking {
            val cards = (1..5).map { swipeCard(userId = "card-$it") }
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = cards, deckSessionId = "deck-1", nextCursor = "cursor-1"),
                    pagesByCursor =
                        mapOf(
                            "cursor-1" to SwipeDeckUi(cards = emptyList(), deckSessionId = "deck-1", nextCursor = null),
                        ),
                )
            val stateHolder = SwipeStateHolder(repository)

            stateHolder.load()
            stateHolder.loadMoreIfNeeded()

            assertEquals(1, repository.deckLoadCount)
        }

    @Test
    fun loadMoreFiltersOutAlreadyVisibleAndPendingCards() =
        runBlocking {
            val first = swipeCard(userId = "shared-1")
            val onlyOnFirstPage = swipeCard(userId = "first-only")
            val duplicate = swipeCard(userId = "shared-1")
            val freshSecondPageCard = swipeCard(userId = "second-only")
            val repository =
                FakeSwipeRepository(
                    deck =
                        SwipeDeckUi(
                            cards = listOf(first, onlyOnFirstPage),
                            deckSessionId = "deck-1",
                            nextCursor = "cursor-1",
                        ),
                    pagesByCursor =
                        mapOf(
                            "cursor-1" to
                                SwipeDeckUi(
                                    cards = listOf(duplicate, freshSecondPageCard),
                                    deckSessionId = "deck-1",
                                    nextCursor = null,
                                ),
                        ),
                    actionResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline", recoverable = true)),
                )
            val stateHolder = SwipeStateHolder(repository, actionIdFactory = { "queued-action" })

            stateHolder.load()
            stateHolder.submitAction(stateHolder.state.value.topCard ?: error("expected top card"), SwipeAction.LIKE)
            stateHolder.loadMoreIfNeeded()

            val state = stateHolder.state.value
            assertEquals(1, state.pendingActionCount)
            assertEquals(listOf("first-only", "second-only"), state.cards.map { it.userId })
        }

    @Test
    fun applyFiltersIssuesFreshDeckRequestMatchingFilters() =
        runBlocking {
            // FILTERS_SPEC L401: applying filters must request a new deck session — not
            // mutate the existing one. The new request must carry the user's filter
            // selection so the server can rank/relax against the right surface.
            val card = swipeCard(userId = "target-1")
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-1"),
                )
            val stateHolder = SwipeStateHolder(repository)

            stateHolder.load()
            assertEquals(1, repository.deckLoadCount)

            val customized =
                SwipeFilters.defaults.copy(
                    singles = true,
                    radiusMeters = 10_000,
                    minimumAge = 30,
                    maximumAge = 45,
                    dealbreakers = setOf(SwipeDealbreakerKey.AGE, SwipeDealbreakerKey.DISTANCE),
                )
            stateHolder.applyFilters(customized)

            assertEquals(2, repository.deckLoadCount)
            val applied = repository.deckRequests.last()
            assertEquals(10_000, applied.radiusMeters)
            assertEquals(30, applied.minimumAge)
            assertEquals(45, applied.maximumAge)
            assertEquals(listOf(SwipeFilters.SINGLES_CONTEXT), applied.relationshipContexts)
            assertEquals(
                setOf(SwipeDealbreakerKey.AGE, SwipeDealbreakerKey.DISTANCE),
                applied.dealbreakers,
            )
            assertNull(applied.cursor)
            assertEquals(customized, stateHolder.state.value.swipeFilters)
        }

    @Test
    fun applyFiltersDefaultsRestoresDefaultRequest() =
        runBlocking {
            // Clear-all in the sheet (C3) goes through applyFilters(defaults). Verify the
            // resulting request DTO matches the default surface so the server hydrates an
            // unfiltered deck — no dealbreakers, default radius, no contexts.
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = emptyList(), deckSessionId = "deck-1"),
                )
            val stateHolder = SwipeStateHolder(repository)

            stateHolder.load()
            stateHolder.applyFilters(SwipeFilters.defaults.copy(linked = true))
            stateHolder.applyFilters(SwipeFilters.defaults)

            assertEquals(3, repository.deckLoadCount)
            val cleared = repository.deckRequests.last()
            assertEquals(SwipeFilters.DEFAULT_RADIUS_METERS, cleared.radiusMeters)
            assertEquals(emptyList<String>(), cleared.relationshipContexts)
            assertEquals(emptySet<String>(), cleared.dealbreakers)
            assertNull(cleared.linkedOnly)
            assertEquals(SwipeFilters.defaults, stateHolder.state.value.swipeFilters)
        }

    @Test
    fun rewindEntitlementFailureShowsPaywallMessage() =
        runBlocking {
            val card = swipeCard(userId = "target-1")
            val repository =
                FakeSwipeRepository(
                    deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-1"),
                    rewindResult =
                        AppResult.Failure(
                            AppError("ENTITLEMENT_REQUIRED", "Upgrade required.", recoverable = false),
                        ),
                )
            val stateHolder = SwipeStateHolder(repository)

            stateHolder.load()
            stateHolder.rewind()

            val state = stateHolder.state.value
            assertEquals(1, repository.rewindCount)
            assertEquals("Rewind is included with Social or Select.", state.paywallMessage)
            assertNull(state.errorMessage)
            assertEquals(card, state.topCard)
        }
}

private class FakeSwipeRepository(
    private val deck: SwipeDeckUi,
    var actionResult: AppResult<MatchDto?> = AppResult.Success(null),
    var rewindResult: AppResult<SwipeRewindUi?> = AppResult.Success(null),
    private val pagesByCursor: Map<String, SwipeDeckUi> = emptyMap(),
) : SwipeRepository {
    var submitCount = 0
        private set
    var rewindCount = 0
        private set
    var deckLoadCount = 0
        private set
    val deckCursors = mutableListOf<String?>()
    val deckRequests = mutableListOf<SwipeDeckRequestDto>()
    val submittedActions = mutableListOf<QueuedSwipeAction>()

    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> {
        deckLoadCount += 1
        deckCursors += request.cursor
        deckRequests += request
        val payload =
            if (request.cursor == null) {
                deck
            } else {
                pagesByCursor[request.cursor!!]
                    ?: return AppResult.Failure(AppError("UNKNOWN_CURSOR", "no fake page for ${request.cursor}", recoverable = false))
            }
        return AppResult.Success(payload)
    }

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> {
        submitCount += 1
        submittedActions += action
        return actionResult
    }

    override suspend fun rewind(): AppResult<SwipeRewindUi?> {
        rewindCount += 1
        return rewindResult
    }
}

private class FakeSwipeActionQueueStore(
    initialActions: Map<String, List<QueuedSwipeAction>> = emptyMap(),
) : SwipeActionQueueStore {
    private val actionsByOwner = initialActions.mapValues { it.value.toMutableList() }.toMutableMap()

    override suspend fun load(ownerUserId: String?): List<QueuedSwipeAction> = actionsByOwner[ownerUserId.ownerKey()].orEmpty()

    override suspend fun save(
        ownerUserId: String?,
        actions: List<QueuedSwipeAction>,
    ) {
        actionsByOwner[ownerUserId.ownerKey()] = actions.toMutableList()
    }

    override suspend fun clear(ownerUserId: String?) {
        actionsByOwner.remove(ownerUserId.ownerKey())
    }

    fun savedActions(ownerUserId: String? = null): List<QueuedSwipeAction> = actionsByOwner[ownerUserId.ownerKey()].orEmpty()

    private fun String?.ownerKey(): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: "anonymous"
}

private fun swipeCard(userId: String): SwipeCardDto =
    SwipeCardDto(
        userId = userId,
        displayName = "Maya",
        rankToken = "swipe:test:$userId",
    )
