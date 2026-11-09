package com.pynapli.app.grid

import com.pynapli.app.savedfilters.SavedFilterRepository
import com.pynapli.app.savedfilters.SavedFilterStateHolder
import com.pynapli.app.savedfilters.SavedFiltersUiState
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.map
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.SavedGridFilterDto

typealias SavedGridFilterRepository = SavedFilterRepository<CreateSavedGridFilterRequestDto, SavedGridFilterDto>

typealias SavedGridFiltersUiState = SavedFiltersUiState<SavedGridFilterDto>

/** Saved Grid filter presets. Grid has no verification prompt row, so an age-verification failure on save is shown as an error. */
class SavedGridFilterStateHolder(
    repository: SavedGridFilterRepository,
) : SavedFilterStateHolder<GridFilters, CreateSavedGridFilterRequestDto, SavedGridFilterDto>(
        repository = repository,
        toRequest = { name, filters -> CreateSavedGridFilterRequestDto(name = name, payload = filters.toGridQueryRequest(cursor = null).copy(cursor = null)) },
        idOf = { it.id },
        createdAtOf = { it.createdAt },
        silentAgeVerificationOnSave = false,
    )

class ApiSavedGridFilterRepository(
    private val apiClient: PynapliApiClient,
) : SavedGridFilterRepository {
    override suspend fun list(): AppResult<List<SavedGridFilterDto>> = apiClient.listSavedGridFilters().map { value -> value.items }

    override suspend fun create(request: CreateSavedGridFilterRequestDto): AppResult<SavedGridFilterDto> = apiClient.createSavedGridFilter(request)

    override suspend fun delete(id: String): AppResult<Unit> = apiClient.deleteSavedGridFilter(id)
}
