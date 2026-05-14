package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class SwipeUiState(
    val deckSessionId: String? = null,
    val cards: List<SwipeCardDto> = emptyList(),
    val filters: List<SwipeFilterUi> = defaultSwipeFilters,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val adSupported: Boolean = false,
    val paywallMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val pendingMatch: MatchDto? = null
) {
    val topCard: SwipeCardDto? get() = cards.firstOrNull()
    val hasMore: Boolean get() = cards.isNotEmpty()
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
    private val repository: SwipeRepository
) {
    private val _state = MutableStateFlow(SwipeUiState())
    val state: StateFlow<SwipeUiState> = _state.asStateFlow()

    suspend fun load() {
        val selectedFilter = _state.value.filters.firstOrNull { it.active }?.type ?: SwipeFilterType.ForYou
        _state.update { it.copy(isLoading = true, errorCode = null, errorMessage = null) }
        when (val result = repository.loadDeck(selectedFilter)) {
            is AppResult.Success -> _state.update {
                it.copy(
                    deckSessionId = result.value.deckSessionId,
                    cards = result.value.cards,
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
        if (_state.value.isSubmitting) return

        var shouldSubmit = false
        _state.update { current ->
            if (current.topCard?.userId != card.userId) {
                current
            } else {
                shouldSubmit = true
                current.copy(
                    cards = current.cards.drop(1),
                    isSubmitting = true,
                    paywallMessage = null,
                    errorCode = null,
                    errorMessage = null
                )
            }
        }
        if (!shouldSubmit) return

        val deckSessionId = _state.value.deckSessionId
        when (val result = repository.submitAction(deckSessionId, card, action, noteText)) {
            is AppResult.Success -> _state.update { current ->
                current.copy(
                    isSubmitting = false,
                    pendingMatch = result.value ?: current.pendingMatch
                )
            }
            is AppResult.Failure -> _state.update {
                it.copy(
                    cards = if (it.cards.any { cardInDeck -> cardInDeck.userId == card.userId }) {
                        it.cards
                    } else {
                        listOf(card) + it.cards
                    },
                    isSubmitting = false,
                    paywallMessage = if (result.error.code == "ENTITLEMENT_REQUIRED") {
                        if (action == SwipeAction.INTRO_NOTE) {
                            "Intro Notes are included with Social or Select."
                        } else {
                            "Social or Select is required."
                        }
                    } else if (result.error.code == "PINEAPPLE_LIKE_INVENTORY_REQUIRED") {
                        "Pineapple Likes are scarce high-intent signals."
                    } else {
                        it.paywallMessage
                    },
                    errorCode = result.error.code,
                    errorMessage = result.error.message
                )
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
}

interface SwipeRepository {
    suspend fun loadDeck(filter: SwipeFilterType): AppResult<SwipeDeckUi>

    suspend fun submitAction(
        deckSessionId: String?,
        card: SwipeCardDto,
        action: String,
        noteText: String? = null
    ): AppResult<MatchDto?>
}

class ApiSwipeRepository(
    private val apiClient: PynapliApiClient
) : SwipeRepository {
    override suspend fun loadDeck(filter: SwipeFilterType): AppResult<SwipeDeckUi> =
        when (val result = apiClient.querySwipeDeck(filter.toRequest())) {
            is AppResult.Success -> AppResult.Success(
                SwipeDeckUi(
                    deckSessionId = result.value.deckSessionId,
                    cards = result.value.items,
                    adSupported = result.value.adSupported,
                    advancedFiltersApplied = result.value.advancedFiltersApplied
                )
            )
            is AppResult.Failure -> result
        }

    override suspend fun submitAction(
        deckSessionId: String?,
        card: SwipeCardDto,
        action: String,
        noteText: String?
    ): AppResult<MatchDto?> =
        when (
            val result = apiClient.submitSwipeAction(
                SwipeActionRequestDto(
                    deckSessionId = deckSessionId.orEmpty(),
                    cardToken = card.rankToken,
                    targetUserId = card.userId,
                    action = action,
                    noteText = noteText
                )
            )
        ) {
            is AppResult.Success -> AppResult.Success(result.value.match)
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

    override suspend fun loadDeck(filter: SwipeFilterType): AppResult<SwipeDeckUi> =
        AppResult.Success(
            SwipeDeckUi(
                deckSessionId = "sample-session",
                cards = cards.toList(),
                adSupported = true,
                advancedFiltersApplied = filter != SwipeFilterType.ForYou
            )
        )

    override suspend fun submitAction(
        deckSessionId: String?,
        card: SwipeCardDto,
        action: String,
        noteText: String?
    ): AppResult<MatchDto?> {
        cards.removeAll { it.userId == card.userId }
        return AppResult.Success(null)
    }
}

@Immutable
data class SwipeDeckUi(
    val deckSessionId: String? = null,
    val cards: List<SwipeCardDto>,
    val adSupported: Boolean = false,
    val advancedFiltersApplied: Boolean = false
)

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

private fun SwipeFilterType.toRequest(): SwipeDeckRequestDto = when (this) {
    SwipeFilterType.ForYou -> SwipeDeckRequestDto(radiusMeters = 25_000, limit = 30)
    SwipeFilterType.Linked -> SwipeDeckRequestDto(radiusMeters = 25_000, limit = 30, linkedOnly = true)
    SwipeFilterType.Age -> SwipeDeckRequestDto(radiusMeters = 25_000, limit = 30, minimumAge = 30, maximumAge = 55)
}

private const val INTRO_NOTE_MAX_LENGTH = 140
