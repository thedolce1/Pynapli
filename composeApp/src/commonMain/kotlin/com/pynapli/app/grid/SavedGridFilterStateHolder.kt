package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.SavedGridFilterDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SavedGridFilterStateHolder(
    private val repository: SavedGridFilterRepository,
) {
    private val _state = MutableStateFlow(SavedGridFiltersUiState())
    val state: StateFlow<SavedGridFiltersUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null, errorCode = null) }
        when (val result = repository.list()) {
            is AppResult.Success ->
                _state.update {
                    it.copy(isLoading = false, items = result.value)
                }
            is AppResult.Failure ->
                _state.update {
                    val entitlement = result.error.code == "ENTITLEMENT_REQUIRED"
                    it.copy(
                        isLoading = false,
                        items = if (entitlement) emptyList() else it.items,
                        errorCode = result.error.code,
                        errorMessage = if (entitlement) null else result.error.message,
                    )
                }
        }
    }

    suspend fun save(
        name: String,
        filters: GridFilters,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(errorCode = "INVALID_SAVED_FILTER_NAME", errorMessage = "Name a saved filter before saving.") }
            return
        }
        _state.update { it.copy(isSaving = true, errorMessage = null, errorCode = null) }
        val payload = filters.toGridQueryRequest(cursor = null).copy(cursor = null)
        when (val result = repository.create(CreateSavedGridFilterRequestDto(name = trimmed, payload = payload))) {
            is AppResult.Success ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        items = (it.items + result.value).sortedByDescending { dto -> dto.createdAt },
                    )
                }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorCode = result.error.code,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    suspend fun delete(id: String) {
        _state.update { it.copy(deletingId = id, errorMessage = null, errorCode = null) }
        when (val result = repository.delete(id)) {
            is AppResult.Success ->
                _state.update {
                    it.copy(
                        deletingId = null,
                        items = it.items.filterNot { dto -> dto.id == id },
                    )
                }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        deletingId = null,
                        errorCode = result.error.code,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null, errorCode = null) }
    }
}

@Immutable
data class SavedGridFiltersUiState(
    val items: List<SavedGridFilterDto> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val deletingId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val entitlementRequired: Boolean get() = errorCode == "ENTITLEMENT_REQUIRED"
}

interface SavedGridFilterRepository {
    suspend fun list(): AppResult<List<SavedGridFilterDto>>

    suspend fun create(request: CreateSavedGridFilterRequestDto): AppResult<SavedGridFilterDto>

    suspend fun delete(id: String): AppResult<Unit>
}

class ApiSavedGridFilterRepository(
    private val apiClient: PynapliApiClient,
) : SavedGridFilterRepository {
    override suspend fun list(): AppResult<List<SavedGridFilterDto>> =
        when (val result = apiClient.listSavedGridFilters()) {
            is AppResult.Success -> AppResult.Success(result.value.items)
            is AppResult.Failure -> result
        }

    override suspend fun create(request: CreateSavedGridFilterRequestDto): AppResult<SavedGridFilterDto> = apiClient.createSavedGridFilter(request)

    override suspend fun delete(id: String): AppResult<Unit> = apiClient.deleteSavedGridFilter(id)
}

@Suppress("UNUSED_PARAMETER")
private fun stripCursor(request: GridQueryRequestDto): GridQueryRequestDto = request.copy(cursor = null)
