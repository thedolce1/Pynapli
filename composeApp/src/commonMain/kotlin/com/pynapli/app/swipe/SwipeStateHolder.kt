package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

@Immutable
data class SwipeUiState(
    val deckSessionId: String? = null,
    val cards: List<SwipeCardDto> = emptyList(),
    val filters: List<SwipeFilterUi> = defaultSwipeFilters,
    val swipeFilters: SwipeFilters = SwipeFilters.defaults,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val isRewinding: Boolean = false,
    val isPaging: Boolean = false,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val pendingActionCount: Int = 0,
    val pendingActionLimit: Int = MAX_PENDING_ACTIONS,
    val paywallMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val pendingMatch: MatchDto? = null
) {
    val topCard: SwipeCardDto? get() = cards.firstOrNull()
    val hasMore: Boolean get() = cards.isNotEmpty() || nextCursor != null
    val actionQueueFull: Boolean get() = pendingActionCount >= pendingActionLimit
}

@Immutable
data class SwipeFilterUi(
    val type: SwipeFilterType,
    val label: String,
    val active: Boolean,
    val locked: Boolean = false
)

enum class SwipeFilterType {
    ForYou,
    Linked,
    Age
}

class SwipeStateHolder(
    private val repository: SwipeRepository,
    private val actionIdFactory: () -> String = ::newSwipeActionId,
    private val queueStore: SwipeActionQueueStore = NoOpSwipeActionQueueStore
) {
    private val _state = MutableStateFlow(SwipeUiState())
    val state: StateFlow<SwipeUiState> = _state.asStateFlow()
    private val pendingActions = mutableListOf<QueuedSwipeAction>()
    private var isFlushingActions = false
    private var restoredQueueOwnerUserId: String? = null

    suspend fun load(ownerUserId: String? = null) {
        restorePendingActions(ownerUserId)
        val swipeFilters = _state.value.swipeFilters
        _state.update {
            it.copy(
                isLoading = true,
                isPaging = false,
                nextCursor = null,
                errorCode = null,
                errorMessage = null
            )
        }
        when (val result = repository.loadDeck(swipeFilters.toSwipeDeckRequest())) {
            is AppResult.Success -> _state.update {
                it.copy(
                    deckSessionId = result.value.deckSessionId,
                    cards = result.value.cards.filterNotPending(),
                    nextCursor = result.value.nextCursor,
                    filters = it.filters.withPaidLocks(result.value.adSupported),
                    adSupported = result.value.adSupported,
                    paywallMessage = null,
                    isLoading = false
                )
            }
            is AppResult.Failure -> _state.update {
                val entitlementRequired = result.error.code == "ENTITLEMENT_REQUIRED"
                it.copy(
                    isLoading = false,
                    nextCursor = null,
                    filters = if (entitlementRequired) {
                        it.filters.withPaidLocks(adSupported = true).withActiveFilter(SwipeFilterType.ForYou)
                    } else {
                        it.filters
                    },
                    paywallMessage = if (entitlementRequired) {
                        "Social or Select unlocks this swipe filter."
                    } else {
                        it.paywallMessage
                    },
                    errorCode = result.error.code,
                    errorMessage = if (entitlementRequired && it.cards.isNotEmpty()) {
                        null
                    } else {
                        result.error.message
                    }
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
            is AppResult.Success -> _state.update { state ->
                val existingIds = state.cards.mapTo(mutableSetOf()) { it.userId }
                val pendingIds = pendingActions.mapTo(mutableSetOf()) { it.card.userId }
                val appended = result.value.cards.filter { card ->
                    card.userId !in existingIds && card.userId !in pendingIds
                }
                state.copy(
                    cards = state.cards + appended,
                    deckSessionId = result.value.deckSessionId ?: state.deckSessionId,
                    nextCursor = result.value.nextCursor,
                    isPaging = false
                )
            }
            is AppResult.Failure -> _state.update {
                it.copy(
                    isPaging = false,
                    errorCode = result.error.code,
                    errorMessage = result.error.message
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
                    errorMessage = null
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
                paywallMessage = null,
                errorCode = null,
                errorMessage = null
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
                paywallMessage = null,
                errorCode = null,
                errorMessage = null
            )
        }
        load()
    }

    suspend fun like() = act(SwipeAction.LIKE)

    suspend fun pass() = act(SwipeAction.PASS)

    suspend fun submitIntroNote(card: SwipeCardDto, noteText: String) {
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

    suspend fun submitAction(card: SwipeCardDto, action: String, noteText: String? = null) {
        if (pendingActions.size >= MAX_PENDING_ACTIONS) {
            _state.update {
                it.copy(
                    errorCode = "SWIPE_ACTION_QUEUE_FULL",
                    errorMessage = "Reconnect to sync pending swipes before swiping more."
                )
            }
            return
        }

        val deckSessionId = _state.value.deckSessionId
        if (_state.value.topCard?.userId != card.userId) return
        val queuedAction = QueuedSwipeAction(
            actionId = actionIdFactory(),
            deckSessionId = deckSessionId,
            card = card,
            action = action,
            noteText = noteText
        )
        pendingActions += queuedAction
        persistPendingActions()
        _state.update { current ->
            current.copy(
                cards = current.cards.drop(1),
                pendingActionCount = pendingActions.size,
                paywallMessage = null,
                errorCode = null,
                errorMessage = null
            )
        }
        flushPendingActions()
    }

    suspend fun retryPendingActions() {
        flushPendingActions()
    }

    suspend fun rewind() {
        val pendingAction = if (pendingActions.isNotEmpty()) {
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
                    errorMessage = null
                )
            }
            return
        }

        _state.update {
            it.copy(
                isRewinding = true,
                paywallMessage = null,
                errorCode = null,
                errorMessage = null
            )
        }
        when (val result = repository.rewind()) {
            is AppResult.Success -> _state.update { current ->
                val rewind = result.value
                if (rewind == null) {
                    current.copy(
                        isRewinding = false,
                        errorCode = null,
                        errorMessage = "Nothing to rewind."
                    )
                } else {
                    current.copy(
                        deckSessionId = rewind.deckSessionId.takeIf { it.isNotBlank() } ?: current.deckSessionId,
                        cards = current.cards.withRestoredTopCard(rewind.card),
                        isRewinding = false,
                        paywallMessage = null,
                        errorCode = null,
                        errorMessage = null
                    )
                }
            }
            is AppResult.Failure -> _state.update {
                it.copy(
                    isRewinding = false,
                    paywallMessage = if (result.error.code == "ENTITLEMENT_REQUIRED") {
                        "Rewind is included with Social or Select."
                    } else {
                        it.paywallMessage
                    },
                    errorCode = result.error.code,
                    errorMessage = if (result.error.code == "ENTITLEMENT_REQUIRED") {
                        null
                    } else {
                        result.error.message
                    }
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
                                pendingMatch = result.value ?: current.pendingMatch
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
                                    errorMessage = "Swipe saved. We will retry when the connection is back."
                                )
                            }
                            return
                        }
                        pendingActions.removeAt(0)
                        persistPendingActions()
                        _state.update {
                            it.copy(
                                cards = if (it.cards.any { cardInDeck -> cardInDeck.userId == queued.card.userId }) {
                                    it.cards
                                } else {
                                    listOf(queued.card) + it.cards
                                },
                                isSubmitting = false,
                                pendingActionCount = pendingActions.size,
                                paywallMessage = queued.paywallMessageFor(result.error.code) ?: it.paywallMessage,
                                errorCode = result.error.code,
                                errorMessage = result.error.message
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
        if (restoredQueueOwnerUserId == ownerUserId) return
        restoredQueueOwnerUserId = ownerUserId
        pendingActions.clear()
        pendingActions += queueStore.load(ownerUserId).take(MAX_PENDING_ACTIONS)
        _state.update {
            it.copy(
                pendingActionCount = pendingActions.size,
                isSubmitting = false
            )
        }
    }

    private suspend fun persistPendingActions() {
        val ownerUserId = restoredQueueOwnerUserId
        runCatching {
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
}

interface SwipeRepository {
    suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi>

    suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?>

    suspend fun rewind(): AppResult<SwipeRewindUi?>
}

class ApiSwipeRepository(
    private val apiClient: PynapliApiClient
) : SwipeRepository {
    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> =
        when (val result = apiClient.querySwipeDeck(request)) {
            is AppResult.Success -> AppResult.Success(
                SwipeDeckUi(
                    deckSessionId = result.value.deckSessionId,
                    cards = result.value.items,
                    nextCursor = result.value.nextCursor,
                    adSupported = result.value.adSupported,
                    advancedFiltersApplied = result.value.advancedFiltersApplied
                )
            )
            is AppResult.Failure -> result
        }

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> =
        when (
            val result = apiClient.submitSwipeAction(
                SwipeActionRequestDto(
                    actionId = action.actionId,
                    deckSessionId = action.deckSessionId.orEmpty(),
                    cardToken = action.card.rankToken,
                    targetUserId = action.card.userId,
                    action = action.action,
                    noteText = action.noteText
                )
            )
        ) {
            is AppResult.Success -> AppResult.Success(result.value.match)
            is AppResult.Failure -> result
        }

    override suspend fun rewind(): AppResult<SwipeRewindUi?> =
        when (val result = apiClient.rewindSwipeAction()) {
            is AppResult.Success -> AppResult.Success(result.value.toUi())
            is AppResult.Failure -> result
        }
}

class SampleSwipeRepository : SwipeRepository {
    private val cards = mutableListOf(
        SwipeCardDto(
            userId = "sample-1",
            displayName = "Maya",
            displayAge = 31,
            distanceLabel = "800 m",
            distanceMeters = 800,
            photoUrl = null,
            linkedPartner = com.pynapli.shared.network.dto.LinkedPartnerMiniDto(
                userId = "sample-linked-1",
                displayName = "Noa"
            ),
            rankToken = "swipe:sample:1"
        ),
        SwipeCardDto(
            userId = "sample-2",
            displayName = "Alex",
            displayAge = 34,
            distanceLabel = "1.2 km",
            distanceMeters = 1_200,
            photoUrl = null,
            rankToken = "swipe:sample:2"
        )
    )

    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> =
        AppResult.Success(
            SwipeDeckUi(
                deckSessionId = "sample-session",
                cards = cards.toList(),
                nextCursor = null,
                adSupported = true,
                advancedFiltersApplied = request.lifestyleTags.isNotEmpty() ||
                    request.logisticsTags.isNotEmpty()
            )
        )

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> {
        cards.removeAll { it.userId == action.card.userId }
        return AppResult.Success(null)
    }

    override suspend fun rewind(): AppResult<SwipeRewindUi?> =
        AppResult.Success(null)
}

@Immutable
data class SwipeDeckUi(
    val deckSessionId: String? = null,
    val cards: List<SwipeCardDto>,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val advancedFiltersApplied: Boolean = false
)

@Immutable
data class SwipeRewindUi(
    val deckSessionId: String,
    val card: SwipeCardDto
)

@Immutable
data class QueuedSwipeAction(
    val actionId: String,
    val deckSessionId: String?,
    val card: SwipeCardDto,
    val action: String,
    val noteText: String? = null
)

private fun QueuedSwipeAction.paywallMessageFor(errorCode: String): String? =
    when (errorCode) {
        "ENTITLEMENT_REQUIRED" -> if (action == SwipeAction.INTRO_NOTE) {
            "Intro Notes are included with Social or Select."
        } else {
            "Social or Select is required."
        }
        "PINEAPPLE_LIKE_INVENTORY_REQUIRED" -> "Pineapple Likes are scarce high-intent signals."
        else -> null
    }

private fun SwipeRewindResponseDto.toUi(): SwipeRewindUi? {
    val restoredCard = card ?: return null
    if (!rewound) return null
    return SwipeRewindUi(deckSessionId = deckSessionId, card = restoredCard)
}

private fun List<SwipeCardDto>.withRestoredTopCard(card: SwipeCardDto): List<SwipeCardDto> =
    if (any { it.userId == card.userId }) {
        this
    } else {
        listOf(card) + this
    }

internal val defaultSwipeFilters = listOf(
    SwipeFilterUi(SwipeFilterType.ForYou, "For You", active = true),
    SwipeFilterUi(SwipeFilterType.Linked, "Linked", active = false),
    SwipeFilterUi(SwipeFilterType.Age, "Age", active = false)
)

internal fun List<SwipeFilterUi>.withActiveFilter(type: SwipeFilterType): List<SwipeFilterUi> =
    map { filter -> filter.copy(active = filter.type == type) }

internal fun List<SwipeFilterUi>.withPaidLocks(adSupported: Boolean): List<SwipeFilterUi> =
    map { filter ->
        filter.copy(
            locked = when (filter.type) {
                SwipeFilterType.ForYou -> false
                SwipeFilterType.Linked,
                SwipeFilterType.Age -> adSupported
            }
        )
    }

internal fun SwipeFilterType.toPresetFilters(): SwipeFilters = when (this) {
    SwipeFilterType.ForYou -> SwipeFilters.forYou
    SwipeFilterType.Linked -> SwipeFilters.linkedOnly
    SwipeFilterType.Age -> SwipeFilters.ageRange
}

private const val INTRO_NOTE_MAX_LENGTH = 140
private const val MAX_PENDING_ACTIONS = 10
private const val LOAD_MORE_THRESHOLD = 3

internal fun newSwipeActionId(random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return buildString(36) {
        append(hex.substring(0, 8))
        append('-')
        append(hex.substring(8, 12))
        append('-')
        append(hex.substring(12, 16))
        append('-')
        append(hex.substring(16, 20))
        append('-')
        append(hex.substring(20, 32))
    }
}
