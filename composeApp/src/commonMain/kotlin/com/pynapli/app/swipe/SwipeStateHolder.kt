package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SwipeStateHolder(
    private val repository: SwipeRepository,
    private val actionIdFactory: () -> String = ::newSwipeActionId,
    private val queueStore: SwipeActionQueueStore = NoOpSwipeActionQueueStore,
) {
    private val _state = MutableStateFlow(SwipeUiState())
    val state: StateFlow<SwipeUiState> = _state.asStateFlow()
    private val pendingActions = mutableListOf<QueuedSwipeAction>()
    private var isFlushingActions = false
    private var restoredQueueOwnerUserId: String? = null

    suspend fun load(ownerUserId: String? = restoredQueueOwnerUserId) {
        val effectiveOwnerUserId = ownerUserId.normalizedOwnerUserId() ?: restoredQueueOwnerUserId
        restorePendingActions(effectiveOwnerUserId)
        val current = _state.value
        val topPicksMode = current.topPicksMode
        val swipeFilters = current.swipeFilters
        _state.update {
            it.copy(
                isLoading = true,
                isPaging = false,
                nextCursor = null,
                errorCode = null,
                errorMessage = null,
            )
        }
        val deckResult =
            if (topPicksMode) {
                repository.loadTopPicks()
            } else {
                repository.loadDeck(swipeFilters.toSwipeDeckRequest())
            }
        when (val result = deckResult) {
            is AppResult.Success ->
                _state.update {
                    it.copy(
                        deckSessionId = result.value.deckSessionId,
                        cards = result.value.cards.filterNotPending(),
                        nextCursor = result.value.nextCursor,
                        filters = it.filters.withPaidLocks(result.value.adSupported),
                        adSupported = result.value.adSupported,
                        paywallMessage = null,
                        isLoading = false,
                    )
                }
            is AppResult.Failure ->
                _state.update {
                    val entitlementRequired = result.error.code == "ENTITLEMENT_REQUIRED"
                    it.copy(
                        isLoading = false,
                        nextCursor = null,
                        topPicksMode = if (entitlementRequired) false else it.topPicksMode,
                        filters =
                            if (entitlementRequired) {
                                it.filters.withPaidLocks(adSupported = true).withActiveFilter(SwipeFilterType.ForYou)
                            } else {
                                it.filters
                            },
                        paywallMessage =
                            if (entitlementRequired) {
                                "Social or Select unlocks this swipe filter."
                            } else {
                                it.paywallMessage
                            },
                        errorCode = result.error.code,
                        errorMessage =
                            if (entitlementRequired && it.cards.isNotEmpty()) {
                                null
                            } else {
                                result.error.message
                            },
                    )
                }
        }
        if (pendingActions.isNotEmpty()) {
            flushPendingActions()
        }
    }

    suspend fun loadMoreIfNeeded() {
        val current = _state.value
        if (current.isPaging || current.isLoading) return
        val cursor = current.nextCursor ?: return
        if (current.cards.size >= LOAD_MORE_THRESHOLD) return
        _state.update { it.copy(isPaging = true) }
        when (val result = repository.loadDeck(current.swipeFilters.toSwipeDeckRequest(cursor = cursor))) {
            is AppResult.Success ->
                _state.update { state ->
                    val existingIds = state.cards.mapTo(mutableSetOf()) { it.userId }
                    val pendingIds = pendingActions.mapTo(mutableSetOf()) { it.card.userId }
                    val appended =
                        result.value.cards.filter { card ->
                            card.userId !in existingIds && card.userId !in pendingIds
                        }
                    state.copy(
                        cards = state.cards + appended,
                        deckSessionId = result.value.deckSessionId ?: state.deckSessionId,
                        nextCursor = result.value.nextCursor,
                        isPaging = false,
                    )
                }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isPaging = false,
                        errorCode = result.error.code,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    suspend fun selectFilter(type: SwipeFilterType) {
        val selected = _state.value.filters.firstOrNull { it.type == type } ?: return
        if (selected.locked) {
            _state.update {
                it.copy(
                    paywallMessage = "${selected.label} is included with Social or Select.",
                    errorCode = null,
                    errorMessage = null,
                )
            }
            return
        }
        if (selected.active) {
            _state.update { it.copy(paywallMessage = null) }
            return
        }
        _state.update {
            it.copy(
                filters = it.filters.withActiveFilter(type),
                swipeFilters = type.toPresetFilters(),
                topPicksMode = type == SwipeFilterType.TopPicks,
                paywallMessage = null,
                errorCode = null,
                errorMessage = null,
            )
        }
        load()
    }

    /**
     * Replace the entire swipe filter state in one shot. Used by the filter sheet
     * (Phase C2+) when the user applies a customized filter set. Triggers a fresh
     * deck load per FILTERS_SPEC L401 ("client should request a new deck session
     * for changes").
     */
    suspend fun applyFilters(filters: SwipeFilters) {
        _state.update {
            it.copy(
                swipeFilters = filters,
                topPicksMode = false,
                filters = it.filters.withActiveFilter(SwipeFilterType.ForYou),
                paywallMessage = null,
                errorCode = null,
                errorMessage = null,
            )
        }
        load()
    }

    suspend fun like() = act(SwipeAction.LIKE)

    suspend fun pass() = act(SwipeAction.PASS)

    suspend fun submitIntroNote(
        card: SwipeCardDto,
        noteText: String,
    ) {
        val trimmed = noteText.trim()
        when {
            trimmed.isBlank() -> {
                _state.update { it.copy(errorMessage = "Intro note cannot be empty.") }
                return
            }
            trimmed.length > INTRO_NOTE_MAX_LENGTH -> {
                _state.update { it.copy(errorMessage = "Intro note must be 140 characters or less.") }
                return
            }
        }
        submitAction(card = card, action = SwipeAction.INTRO_NOTE, noteText = trimmed)
    }

    suspend fun submitAction(
        card: SwipeCardDto,
        action: String,
        noteText: String? = null,
    ) {
        if (pendingActions.size >= MAX_PENDING_ACTIONS) {
            _state.update {
                it.copy(
                    errorCode = "SWIPE_ACTION_QUEUE_FULL",
                    errorMessage = "Reconnect to sync pending swipes before swiping more.",
                )
            }
            return
        }

        val deckSessionId = _state.value.deckSessionId
        if (_state.value.topCard?.userId != card.userId) return
        val queuedAction =
            QueuedSwipeAction(
                actionId = actionIdFactory(),
                deckSessionId = deckSessionId,
                card = card,
                action = action,
                noteText = noteText,
            )
        pendingActions += queuedAction
        persistPendingActions()
        _state.update { current ->
            current.copy(
                cards = current.cards.drop(1),
                pendingActionCount = pendingActions.size,
                paywallMessage = null,
                errorCode = null,
                errorMessage = null,
            )
        }
        flushPendingActions()
    }

    suspend fun retryPendingActions() {
        flushPendingActions()
    }

    suspend fun rewind() {
        val pendingAction =
            if (pendingActions.isNotEmpty()) {
                pendingActions.removeAt(pendingActions.lastIndex)
            } else {
                null
            }
        if (pendingAction != null) {
            persistPendingActions()
            _state.update { current ->
                current.copy(
                    cards = current.cards.withRestoredTopCard(pendingAction.card),
                    pendingActionCount = pendingActions.size,
                    isSubmitting = pendingActions.isNotEmpty(),
                    paywallMessage = null,
                    errorCode = null,
                    errorMessage = null,
                )
            }
            return
        }

        _state.update {
            it.copy(
                isRewinding = true,
                paywallMessage = null,
                errorCode = null,
                errorMessage = null,
            )
        }
        when (val result = repository.rewind()) {
            is AppResult.Success ->
                _state.update { current ->
                    val rewind = result.value
                    if (rewind == null) {
                        current.copy(
                            isRewinding = false,
                            errorCode = null,
                            errorMessage = "Nothing to rewind.",
                        )
                    } else {
                        current.copy(
                            deckSessionId = rewind.deckSessionId.takeIf { it.isNotBlank() } ?: current.deckSessionId,
                            cards = current.cards.withRestoredTopCard(rewind.card),
                            isRewinding = false,
                            paywallMessage = null,
                            errorCode = null,
                            errorMessage = null,
                        )
                    }
                }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isRewinding = false,
                        paywallMessage =
                            if (result.error.code == "ENTITLEMENT_REQUIRED") {
                                "Rewind is included with Social or Select."
                            } else {
                                it.paywallMessage
                            },
                        errorCode = result.error.code,
                        errorMessage =
                            if (result.error.code == "ENTITLEMENT_REQUIRED") {
                                null
                            } else {
                                result.error.message
                            },
                    )
                }
        }
    }

    private suspend fun flushPendingActions() {
        if (isFlushingActions) return
        isFlushingActions = true
        _state.update { it.copy(isSubmitting = pendingActions.isNotEmpty()) }
        try {
            while (pendingActions.isNotEmpty()) {
                val queued = pendingActions.first()
                when (val result = repository.submitAction(queued)) {
                    is AppResult.Success -> {
                        pendingActions.removeAt(0)
                        persistPendingActions()
                        _state.update { current ->
                            current.copy(
                                isSubmitting = pendingActions.isNotEmpty(),
                                pendingActionCount = pendingActions.size,
                                errorCode = null,
                                errorMessage = null,
                                pendingMatch = result.value ?: current.pendingMatch,
                            )
                        }
                    }
                    is AppResult.Failure -> {
                        if (result.error.recoverable) {
                            _state.update {
                                it.copy(
                                    isSubmitting = false,
                                    pendingActionCount = pendingActions.size,
                                    errorCode = result.error.code,
                                    errorMessage = "Swipe saved. We will retry when the connection is back.",
                                )
                            }
                            return
                        }
                        pendingActions.removeAt(0)
                        persistPendingActions()
                        _state.update {
                            it.copy(
                                cards =
                                    if (it.cards.any { cardInDeck -> cardInDeck.userId == queued.card.userId }) {
                                        it.cards
                                    } else {
                                        listOf(queued.card) + it.cards
                                    },
                                isSubmitting = false,
                                pendingActionCount = pendingActions.size,
                                paywallMessage = queued.paywallMessageFor(result.error.code) ?: it.paywallMessage,
                                errorCode = result.error.code,
                                errorMessage = result.error.message,
                            )
                        }
                        return
                    }
                }
            }
        } finally {
            isFlushingActions = false
            if (pendingActions.isEmpty()) {
                _state.update { it.copy(isSubmitting = false, pendingActionCount = 0) }
            }
        }
    }

    fun dismissMatch() {
        _state.update { it.copy(pendingMatch = null) }
    }

    fun removeCardById(userId: String) {
        _state.update { current ->
            current.copy(cards = current.cards.filterNot { it.userId == userId })
        }
    }

    private suspend fun act(action: String) {
        val card = _state.value.topCard ?: return
        submitAction(card, action)
    }

    private suspend fun restorePendingActions(ownerUserId: String?) {
        val normalizedOwnerUserId = ownerUserId.normalizedOwnerUserId()
        if (restoredQueueOwnerUserId == normalizedOwnerUserId) return
        val ownerActions = queueStore.load(normalizedOwnerUserId)
        val anonymousActions =
            if (normalizedOwnerUserId != null) {
                queueStore.load(null)
            } else {
                emptyList()
            }
        restoredQueueOwnerUserId = normalizedOwnerUserId
        pendingActions.clear()
        pendingActions +=
            (ownerActions + anonymousActions)
                .distinctBy { it.actionId }
                .take(MAX_PENDING_ACTIONS)
        if (normalizedOwnerUserId != null && anonymousActions.isNotEmpty()) {
            queueStore.clear(null)
            queueStore.save(normalizedOwnerUserId, pendingActions)
        }
        _state.update {
            it.copy(
                pendingActionCount = pendingActions.size,
                isSubmitting = false,
            )
        }
    }

    private suspend fun persistPendingActions() {
        val ownerUserId = restoredQueueOwnerUserId
        runCatchingCancellable {
            if (pendingActions.isEmpty()) {
                queueStore.clear(ownerUserId)
            } else {
                queueStore.save(ownerUserId, pendingActions.take(MAX_PENDING_ACTIONS))
            }
        }
    }

    private fun List<SwipeCardDto>.filterNotPending(): List<SwipeCardDto> {
        if (pendingActions.isEmpty()) return this
        val pendingTargetIds = pendingActions.mapTo(mutableSetOf()) { it.card.userId }
        return filterNot { it.userId in pendingTargetIds }
    }

    private fun String?.normalizedOwnerUserId(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
