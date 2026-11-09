package com.pynapli.app.swipe

import com.pynapli.app.savedfilters.SavedFilterRepository
import com.pynapli.app.savedfilters.SavedFilterStateHolder
import com.pynapli.app.savedfilters.SavedFiltersUiState
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.map
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto

typealias SavedSwipeFilterRepository = SavedFilterRepository<CreateSavedSwipeFilterRequestDto, SavedSwipeFilterDto>

typealias SavedSwipeFiltersUiState = SavedFiltersUiState<SavedSwipeFilterDto>

/** Saved Swipe filter presets. Swipe surfaces age verification through its prompt row, so that failure on save stays silent instead of a raw error toast. */
class SavedSwipeFilterStateHolder(
    repository: SavedSwipeFilterRepository,
) : SavedFilterStateHolder<SwipeFilters, CreateSavedSwipeFilterRequestDto, SavedSwipeFilterDto>(
        repository = repository,
        toRequest = { name, filters -> CreateSavedSwipeFilterRequestDto(name = name, payload = filters.toSwipeDeckRequest(cursor = null)) },
        idOf = { it.id },
        createdAtOf = { it.createdAt },
        silentAgeVerificationOnSave = true,
    )

class ApiSavedSwipeFilterRepository(
    private val apiClient: PynapliApiClient,
) : SavedSwipeFilterRepository {
    override suspend fun list(): AppResult<List<SavedSwipeFilterDto>> = apiClient.listSavedSwipeFilters().map { value -> value.items }

    override suspend fun create(request: CreateSavedSwipeFilterRequestDto): AppResult<SavedSwipeFilterDto> = apiClient.createSavedSwipeFilter(request)

    override suspend fun delete(id: String): AppResult<Unit> = apiClient.deleteSavedSwipeFilter(id)
}
