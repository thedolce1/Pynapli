package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Client-side store for saved swipe filter presets (FILTERS_SPEC L463-477).
 * Mirrors `SavedGridFilterStateHolder` — load on sheet open, save current draft,
 * delete by id. Entitlement failures collapse the items list and flip
 * `entitlementRequired` so the UI can show the paywall copy instead of an empty
 * "no presets" state.
 */
class SavedSwipeFilterStateHolder(
    private val repository: SavedSwipeFilterRepository,
) {
    private val _state = MutableStateFlow(SavedSwipeFiltersUiState())
    val state: StateFlow<SavedSwipeFiltersUiState> = _state.asStateFlow()

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
                    val verification = result.error.code == "AGE_VERIFICATION_REQUIRED"
                    it.copy(
                        isLoading = false,
                        items = if (entitlement || verification) emptyList() else it.items,
                        errorCode = result.error.code,
                        errorMessage = if (entitlement || verification) null else result.error.message,
                    )
                }
        }
    }

    suspend fun save(
        name: String,
        filters: SwipeFilters,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update {
                it.copy(
                    errorCode = "INVALID_SAVED_FILTER_NAME",
                    errorMessage = "Name a saved filter before saving.",
                )
            }
            return
        }
        _state.update { it.copy(isSaving = true, errorMessage = null, errorCode = null) }
        val payload = filters.toSwipeDeckRequest(cursor = null)
        when (
            val result =
                repository.create(
                    CreateSavedSwipeFilterRequestDto(name = trimmed, payload = payload),
                )
        ) {
            is AppResult.Success ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        items = (it.items + result.value).sortedByDescending { dto -> dto.createdAt },
                    )
                }
            is AppResult.Failure ->
                _state.update {
                    val verification = result.error.code == "AGE_VERIFICATION_REQUIRED"
                    it.copy(
                        isSaving = false,
                        errorCode = result.error.code,
                        // Age-verification is surfaced via the prompt row, not a raw error toast.
                        errorMessage = if (verification) null else result.error.message,
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
data class SavedSwipeFiltersUiState(
    val items: List<SavedSwipeFilterDto> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val deletingId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val entitlementRequired: Boolean get() = errorCode == "ENTITLEMENT_REQUIRED"
    val ageVerificationRequired: Boolean get() = errorCode == "AGE_VERIFICATION_REQUIRED"
}

interface SavedSwipeFilterRepository {
    suspend fun list(): AppResult<List<SavedSwipeFilterDto>>

    suspend fun create(request: CreateSavedSwipeFilterRequestDto): AppResult<SavedSwipeFilterDto>

    suspend fun delete(id: String): AppResult<Unit>
}

class ApiSavedSwipeFilterRepository(
    private val apiClient: PynapliApiClient,
) : SavedSwipeFilterRepository {
    override suspend fun list(): AppResult<List<SavedSwipeFilterDto>> =
        when (val result = apiClient.listSavedSwipeFilters()) {
            is AppResult.Success -> AppResult.Success(result.value.items)
            is AppResult.Failure -> result
        }

    override suspend fun create(request: CreateSavedSwipeFilterRequestDto): AppResult<SavedSwipeFilterDto> = apiClient.createSavedSwipeFilter(request)

    override suspend fun delete(id: String): AppResult<Unit> = apiClient.deleteSavedSwipeFilter(id)
}
