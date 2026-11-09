package com.pynapli.app.savedfilters

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val ENTITLEMENT_REQUIRED = "ENTITLEMENT_REQUIRED"
private const val AGE_VERIFICATION_REQUIRED = "AGE_VERIFICATION_REQUIRED"
private const val INVALID_NAME = "INVALID_SAVED_FILTER_NAME"

interface SavedFilterRepository<Req, Item> {
    suspend fun list(): AppResult<List<Item>>

    suspend fun create(request: Req): AppResult<Item>

    suspend fun delete(id: String): AppResult<Unit>
}

@Immutable
data class SavedFiltersUiState<Item>(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val deletingId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val entitlementRequired: Boolean get() = errorCode == ENTITLEMENT_REQUIRED
    val ageVerificationRequired: Boolean get() = errorCode == AGE_VERIFICATION_REQUIRED
}

/**
 * Saved filter presets (FILTERS_SPEC L463-477), shared by Grid and Swipe: load when the sheet opens,
 * save the current draft under a name, delete by id. Entitlement and age-verification failures on load
 * clear the list and set [SavedFiltersUiState.entitlementRequired] / `ageVerificationRequired`, so
 * the UI shows the paywall or verification prompt instead of an empty "no presets" state.
 *
 * Subclasses supply how a filter set becomes a create request ([toRequest]), and whether an
 * age-verification failure on save should stay silent because the surface already shows a prompt
 * row for it ([silentAgeVerificationOnSave]).
 */
open class SavedFilterStateHolder<F, Req, Item>(
    private val repository: SavedFilterRepository<Req, Item>,
    private val toRequest: (name: String, filters: F) -> Req,
    private val idOf: (Item) -> String,
    private val createdAtOf: (Item) -> String,
    private val silentAgeVerificationOnSave: Boolean = false,
) {
    private val _state = MutableStateFlow(SavedFiltersUiState<Item>())
    val state: StateFlow<SavedFiltersUiState<Item>> = _state.asStateFlow()

    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null, errorCode = null) }
        when (val result = repository.list()) {
            is AppResult.Success -> _state.update { it.copy(isLoading = false, items = result.value) }
            is AppResult.Failure ->
                _state.update {
                    val gated = result.error.code == ENTITLEMENT_REQUIRED || result.error.code == AGE_VERIFICATION_REQUIRED
                    it.copy(
                        isLoading = false,
                        items = if (gated) emptyList() else it.items,
                        errorCode = result.error.code,
                        errorMessage = if (gated) null else result.error.message,
                    )
                }
        }
    }

    suspend fun save(
        name: String,
        filters: F,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(errorCode = INVALID_NAME, errorMessage = "Name a saved filter before saving.") }
            return
        }
        _state.update { it.copy(isSaving = true, errorMessage = null, errorCode = null) }
        when (val result = repository.create(toRequest(trimmed, filters))) {
            is AppResult.Success ->
                _state.update {
                    it.copy(isSaving = false, items = (it.items + result.value).sortedByDescending(createdAtOf))
                }
            is AppResult.Failure ->
                _state.update {
                    val silent = silentAgeVerificationOnSave && result.error.code == AGE_VERIFICATION_REQUIRED
                    it.copy(
                        isSaving = false,
                        errorCode = result.error.code,
                        errorMessage = if (silent) null else result.error.message,
                    )
                }
        }
    }

    suspend fun delete(id: String) {
        _state.update { it.copy(deletingId = id, errorMessage = null, errorCode = null) }
        when (val result = repository.delete(id)) {
            is AppResult.Success ->
                _state.update { it.copy(deletingId = null, items = it.items.filterNot { item -> idOf(item) == id }) }
            is AppResult.Failure ->
                _state.update {
                    it.copy(deletingId = null, errorCode = result.error.code, errorMessage = result.error.message)
                }
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null, errorCode = null) }
    }
}
