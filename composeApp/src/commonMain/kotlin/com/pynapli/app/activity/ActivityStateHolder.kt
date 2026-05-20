package com.pynapli.app.activity

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class ActivityUiState(
    val items: List<ActivityEventDto> = emptyList(),
    val selectedTab: ActivityTabUi = ActivityTabUi.All,
    val summary: ActivitySummaryUi = ActivitySummaryUi.Empty,
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val adSupported: Boolean = false,
    val entitlementTier: String? = null,
    val revealLimit: Int? = null,
    val gatedCount: Int = 0,
    val pendingAlbumActionEventId: String? = null,
    val pendingDismissEventId: String? = null,
    val actionFeedback: String? = null,
    val errorMessage: String? = null
) {
    val visibleItems: List<ActivityEventDto>
        get() = items

    val activityBadgeCount: Int
        get() = summary.badgeCount
}

@Immutable
data class ActivitySummaryUi(
    val badgeCount: Int,
    val profileViewCount: Int,
    val flirtCount: Int,
    val likeCount: Int,
    val matchCount: Int,
    val albumRequestCount: Int,
    val partnerInviteCount: Int,
    val latestBadgeOccurredAt: String?
) {
    companion object {
        val Empty = ActivitySummaryUi(
            badgeCount = 0,
            profileViewCount = 0,
            flirtCount = 0,
            likeCount = 0,
            matchCount = 0,
            albumRequestCount = 0,
            partnerInviteCount = 0,
            latestBadgeOccurredAt = null
        )
    }
}

enum class ActivityTabUi(
    val label: String,
    val apiValue: String
) {
    All("All", "all"),
    Views("Views", "views"),
    Flirts("Flirts", "flirts"),
    Likes("Likes", "likes"),
    Matches("Matches", "matches"),
    Albums("Albums", "albums"),
    Invites("Invites", "invites")
}

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
                actionFeedback = null,
                errorMessage = null
            )
        }
        refresh(showLoading = true, surfaceErrors = true, markLoadedItemsSeen = true)
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

interface ActivityFeedRepository {
    suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String? = null,
        limit: Int = ActivityFeedUi.DEFAULT_PAGE_SIZE
    ): AppResult<ActivityFeedUi>

    suspend fun loadSummary(): AppResult<ActivitySummaryUi>

    suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto>

    suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto>

    suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean
    ): AppResult<RespondAlbumAccessResponseDto>
}

class ApiActivityFeedRepository(
    private val apiClient: PynapliApiClient
) : ActivityFeedRepository {
    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int
    ): AppResult<ActivityFeedUi> =
        when (val result = apiClient.listActivity(limit = limit, tab = tab.apiValue, cursor = cursor)) {
            is AppResult.Success -> AppResult.Success(
                ActivityFeedUi(
                    items = result.value.items,
                    nextCursor = result.value.nextCursor,
                    adSupported = result.value.adSupported,
                    entitlementTier = result.value.entitlementTier,
                    revealLimit = result.value.revealLimit,
                    gatedCount = result.value.gatedCount
                )
            )
            is AppResult.Failure -> result
        }

    override suspend fun loadSummary(): AppResult<ActivitySummaryUi> =
        when (val result = apiClient.getActivitySummary()) {
            is AppResult.Success -> AppResult.Success(
                ActivitySummaryUi(
                    badgeCount = result.value.badgeCount,
                    profileViewCount = result.value.profileViewCount,
                    flirtCount = result.value.flirtCount,
                    likeCount = result.value.likeCount,
                    matchCount = result.value.matchCount,
                    albumRequestCount = result.value.albumRequestCount,
                    partnerInviteCount = result.value.partnerInviteCount,
                    latestBadgeOccurredAt = result.value.latestBadgeOccurredAt
                )
            )

            is AppResult.Failure -> result
        }

    override suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto> =
        apiClient.markActivitySeen(MarkActivitySeenRequestDto(eventIds = eventIds))

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> =
        apiClient.dismissActivityEvent(eventId)

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean
    ): AppResult<RespondAlbumAccessResponseDto> =
        apiClient.respondToAlbumAccessRequest(
            eventId = eventId,
            request = RespondAlbumAccessRequestDto(approve = approve)
        )
}

class SampleActivityFeedRepository : ActivityFeedRepository {
    override suspend fun loadActivity(
        tab: ActivityTabUi,
        cursor: String?,
        limit: Int
    ): AppResult<ActivityFeedUi> {
        val allItems = listOf(
            ActivityEventDto(
                eventId = "sample-1",
                type = "profile_view",
                actorUserId = "1",
                actorDisplayName = "Maya",
                count = 3,
                occurredAt = "2026-04-25T18:00:00Z",
                distanceLabel = "1.2 km",
                distanceMeters = 1_200
            ),
            ActivityEventDto(
                eventId = "sample-2",
                type = "flirt",
                actorUserId = "2",
                actorDisplayName = "Alex",
                occurredAt = "2026-04-25T17:45:00Z"
            ),
            ActivityEventDto(
                eventId = "sample-3",
                type = "album_request",
                actorUserId = "3",
                actorDisplayName = "Sam",
                occurredAt = "2026-04-25T17:10:00Z",
                albumRequestStatus = "pending"
            ),
            ActivityEventDto(
                eventId = "sample-4",
                type = "match",
                actorUserId = "4",
                actorDisplayName = "Taylor",
                occurredAt = "2026-04-25T16:40:00Z",
                matchId = "sample-match"
            ),
            ActivityEventDto(
                eventId = "sample-5",
                type = "partner_chat_invite",
                actorUserId = "5",
                actorDisplayName = "Noa",
                occurredAt = "2026-04-25T16:20:00Z",
                conversationId = "sample-conversation"
            )
        )
        val filteredItems = allItems.filter { item -> tab.matches(item.type) }
        return AppResult.Success(
            ActivityFeedUi(
                items = if (cursor == null) filteredItems.take(limit) else emptyList(),
                nextCursor = null
            )
        )
    }

    override suspend fun loadSummary(): AppResult<ActivitySummaryUi> =
        AppResult.Success(
            ActivitySummaryUi(
                badgeCount = 4,
                profileViewCount = 3,
                flirtCount = 1,
                likeCount = 0,
                matchCount = 1,
                albumRequestCount = 1,
                partnerInviteCount = 1,
                latestBadgeOccurredAt = "2026-04-25T17:45:00Z"
            )
        )

    override suspend fun markActivitySeen(eventIds: List<String>): AppResult<MarkActivitySeenResponseDto> =
        AppResult.Success(MarkActivitySeenResponseDto(markedCount = eventIds.distinct().size))

    override suspend fun dismissActivityEvent(eventId: String): AppResult<DismissActivityEventResponseDto> =
        AppResult.Success(
            DismissActivityEventResponseDto(
                eventId = eventId,
                dismissed = true
            )
        )

    override suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean
    ): AppResult<RespondAlbumAccessResponseDto> =
        AppResult.Success(
            RespondAlbumAccessResponseDto(
                eventId = eventId,
                status = if (approve) "approved" else "denied"
            )
        )
}

@Immutable
data class ActivityFeedUi(
    val items: List<ActivityEventDto>,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val entitlementTier: String? = null,
    val revealLimit: Int? = null,
    val gatedCount: Int = 0
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 30
    }
}

private fun ActivityTabUi.matches(type: String): Boolean = when (this) {
    ActivityTabUi.All -> true
    ActivityTabUi.Views -> type == "profile_view"
    ActivityTabUi.Flirts -> type == "flirt"
    ActivityTabUi.Likes -> type in setOf("swipe_like", "pineapple_like", "intro_note")
    ActivityTabUi.Matches -> type == "match"
    ActivityTabUi.Albums -> type == "album_request"
    ActivityTabUi.Invites -> type in setOf("partner_chat_invite", "psync_invite", "playing_tonight")
}
