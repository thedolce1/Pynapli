package com.pynapli.app.activity

import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ActivityStateHolder(
    private val repository: ActivityFeedRepository
) {
    private val _state = MutableStateFlow(ActivityUiState(isLoading = true))
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()
    private var loadMoreInFlight = false
    private var refreshGeneration = 0

    suspend fun load() {
        refresh(showLoading = true, surfaceErrors = true, markLoadedItemsSeen = true)
    }

    suspend fun refreshSilently() {
        refresh(showLoading = false, surfaceErrors = false, markLoadedItemsSeen = false)
    }

    suspend fun refreshSummarySilently() = refreshSummary(surfaceErrors = false)

    fun reset() {
        loadMoreInFlight = false
        refreshGeneration += 1
        _state.value = ActivityUiState()
    }

    suspend fun selectTab(tab: ActivityTabUi) {
        if (_state.value.selectedTab == tab && _state.value.items.isNotEmpty()) return
        _state.update { current ->
            current.copy(
                selectedTab = tab,
                items = emptyList(),
                nextCursor = null,
                gatedCount = 0,
                focusedEventId = null,
                actionFeedback = null,
                errorMessage = null
            )
        }
        refresh(showLoading = true, surfaceErrors = true, markLoadedItemsSeen = true)
    }

    fun focusEvent(eventId: String?) {
        _state.update { current -> current.copy(focusedEventId = eventId) }
    }

    private suspend fun refresh(
        showLoading: Boolean,
        surfaceErrors: Boolean,
        markLoadedItemsSeen: Boolean
    ) {
        val generation = ++refreshGeneration
        if (showLoading) {
            _state.update { current ->
                current.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    errorMessage = null
                )
            }
        }
        val selectedTab = _state.value.selectedTab

        when (val result = repository.loadActivity(tab = selectedTab)) {
            is AppResult.Success -> {
                var accepted = false
                _state.update { current ->
                    if (generation != refreshGeneration || current.selectedTab != selectedTab) {
                        current
                    } else {
                        accepted = true
                        current.copy(
                            items = result.value.items,
                            nextCursor = result.value.nextCursor,
                            adSupported = result.value.adSupported,
                            entitlementTier = result.value.entitlementTier,
                            revealLimit = result.value.revealLimit,
                            gatedCount = result.value.gatedCount,
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = null
                        )
                    }
                }
                if (accepted) {
                    if (markLoadedItemsSeen) {
                        markEventsSeen(result.value.items.map { it.eventId })
                    }
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    if (generation != refreshGeneration || current.selectedTab != selectedTab) {
                        current
                    } else {
                        current.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = if (surfaceErrors || current.items.isEmpty()) {
                                result.error.message
                            } else {
                                current.errorMessage
                            }
                        )
                    }
                }
                if (markLoadedItemsSeen) {
                    refreshSummary(surfaceErrors = false)
                }
            }
        }
    }

    suspend fun loadMore() {
        val current = _state.value
        val cursor = current.nextCursor ?: return
        if (current.isLoading || current.isLoadingMore || loadMoreInFlight) return
        loadMoreInFlight = true
        _state.update { it.copy(isLoadingMore = true, errorMessage = null) }

        try {
            when (val result = repository.loadActivity(tab = current.selectedTab, cursor = cursor)) {
                is AppResult.Success -> {
                    var appendedEventIds = emptyList<String>()
                    _state.update { existing ->
                        if (existing.selectedTab != current.selectedTab) return@update existing
                        val existingIds = existing.items.mapTo(mutableSetOf()) { it.eventId }
                        val appended = result.value.items.filterNot { it.eventId in existingIds }
                        appendedEventIds = appended.map { it.eventId }
                        existing.copy(
                            items = existing.items + appended,
                            nextCursor = result.value.nextCursor,
                            adSupported = result.value.adSupported,
                            entitlementTier = result.value.entitlementTier,
                            revealLimit = result.value.revealLimit,
                            gatedCount = result.value.gatedCount,
                            isLoadingMore = false,
                            errorMessage = null
                        )
                    }
                    markEventsSeen(appendedEventIds)
                }

                is AppResult.Failure -> {
                    _state.update { existing ->
                        if (existing.selectedTab != current.selectedTab) return@update existing
                        existing.copy(
                            isLoadingMore = false,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        } finally {
            loadMoreInFlight = false
        }
    }

    suspend fun respondToAlbumRequest(eventId: String, approve: Boolean) {
        _state.update { current ->
            current.copy(
                pendingAlbumActionEventId = eventId,
                actionFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.respondToAlbumRequest(eventId, approve)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        items = current.items.map { item ->
                            if (item.eventId == eventId) {
                                item.copy(albumRequestStatus = result.value.status)
                            } else {
                                item
                            }
                        },
                        pendingAlbumActionEventId = null,
                        actionFeedback = if (result.value.status == "approved") {
                            "Album access granted"
                        } else {
                            "Album request denied"
                        }
                    )
                }
                refreshSummary(surfaceErrors = false)
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        pendingAlbumActionEventId = null,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun dismissActivityEvent(eventId: String) {
        _state.update { current ->
            current.copy(
                pendingDismissEventId = eventId,
                actionFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.dismissActivityEvent(eventId)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        items = current.items.filterNot { it.eventId == result.value.eventId },
                        pendingDismissEventId = null,
                        actionFeedback = "Activity dismissed"
                    )
                }
                refreshSummary(surfaceErrors = false)
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        pendingDismissEventId = null,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    private suspend fun markEventsSeen(eventIds: List<String>) {
        val uniqueEventIds = eventIds.distinct()
        if (uniqueEventIds.isEmpty()) {
            refreshSummary(surfaceErrors = false)
            return
        }
        when (repository.markActivitySeen(uniqueEventIds)) {
            is AppResult.Success,
            is AppResult.Failure -> refreshSummary(surfaceErrors = false)
        }
    }

    private suspend fun refreshSummary(surfaceErrors: Boolean) {
        when (val result = repository.loadSummary()) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        summary = result.value,
                        errorMessage = if (surfaceErrors) null else current.errorMessage
                    )
                }
            }

            is AppResult.Failure -> {
                if (surfaceErrors) {
                    _state.update { current ->
                        current.copy(errorMessage = result.error.message)
                    }
                }
            }
        }
    }
}
