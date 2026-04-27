package com.pynapli.app.activity

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class ActivityUiState(
    val items: List<ActivityEventDto> = emptyList(),
    val selectedFilter: ActivityFilterUi = ActivityFilterUi.All,
    val isLoading: Boolean = false,
    val pendingAlbumActionEventId: String? = null,
    val actionFeedback: String? = null,
    val errorMessage: String? = null
) {
    val visibleItems: List<ActivityEventDto>
        get() = when (selectedFilter) {
            ActivityFilterUi.All -> items
            ActivityFilterUi.Views -> items.filter { it.type == "profile_view" }
            ActivityFilterUi.Sparks -> items.filter { it.type == "flirt" }
            ActivityFilterUi.Albums -> items.filter { it.type == "album_request" }
        }
}

enum class ActivityFilterUi(
    val label: String
) {
    All("All"),
    Views("Views"),
    Sparks("Sparks"),
    Albums("Albums")
}

class ActivityStateHolder(
    private val repository: ActivityFeedRepository
) {
    private val _state = MutableStateFlow(ActivityUiState(isLoading = true))
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { current -> current.copy(isLoading = true, errorMessage = null) }
        when (val result = repository.loadActivity()) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        items = result.value,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun selectFilter(filter: ActivityFilterUi) {
        _state.update { current -> current.copy(selectedFilter = filter) }
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
}

interface ActivityFeedRepository {
    suspend fun loadActivity(): AppResult<List<ActivityEventDto>>

    suspend fun respondToAlbumRequest(
        eventId: String,
        approve: Boolean
    ): AppResult<RespondAlbumAccessResponseDto>
}

class ApiActivityFeedRepository(
    private val apiClient: PynapliApiClient
) : ActivityFeedRepository {
    override suspend fun loadActivity(): AppResult<List<ActivityEventDto>> =
        when (val result = apiClient.listActivity()) {
            is AppResult.Success -> AppResult.Success(result.value.items)
            is AppResult.Failure -> result
        }

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
    override suspend fun loadActivity(): AppResult<List<ActivityEventDto>> =
        AppResult.Success(
            listOf(
                ActivityEventDto(
                    eventId = "sample-1",
                    type = "profile_view",
                    actorUserId = "1",
                    actorDisplayName = "Maya",
                    count = 3,
                    occurredAt = "2026-04-25T18:00:00Z",
                    distanceLabel = "1.2 km"
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
                )
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
