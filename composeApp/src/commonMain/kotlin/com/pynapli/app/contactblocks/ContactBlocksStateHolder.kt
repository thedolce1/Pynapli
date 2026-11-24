package com.pynapli.app.contactblocks

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ContactBlockSummaryDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "Hide from people you know" (PROFILE_SETTINGS_SPEC). The server decides everything and tells the
 * client nothing about whether an address belongs to a member; this holder only calls the API and
 * shows the count. The confirmation after an add or a remove is the same for every address.
 */
@Immutable
data class ContactBlocksUiState(
    val count: Int? = null,
    val maxAddresses: Int? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val confirmation: String? = null,
    val errorMessage: String? = null,
) {
    val countLabel: String
        get() =
            when (val added = count) {
                null -> ""
                1 -> "1 address added"
                else -> "$added addresses added"
            }
}

interface ContactBlocksRepository {
    suspend fun loadSummary(): AppResult<ContactBlockSummaryDto>

    suspend fun add(email: String): AppResult<Unit>

    suspend fun remove(email: String): AppResult<Unit>

    suspend fun removeAll(): AppResult<Unit>
}

class ApiContactBlocksRepository(
    private val apiClient: PynapliApiClient,
) : ContactBlocksRepository {
    override suspend fun loadSummary(): AppResult<ContactBlockSummaryDto> = apiClient.getContactBlockSummary()

    override suspend fun add(email: String): AppResult<Unit> = apiClient.addContactBlock(email)

    override suspend fun remove(email: String): AppResult<Unit> = apiClient.removeContactBlock(email)

    override suspend fun removeAll(): AppResult<Unit> = apiClient.removeAllContactBlocks()
}

class ContactBlocksStateHolder(
    private val repository: ContactBlocksRepository,
) {
    private val _state = MutableStateFlow(ContactBlocksUiState())
    val state: StateFlow<ContactBlocksUiState> = _state.asStateFlow()

    suspend fun load() {
        if (state.value.isLoading) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        when (val result = repository.loadSummary()) {
            is AppResult.Success ->
                _state.update {
                    it.copy(isLoading = false, count = result.value.count, maxAddresses = result.value.maxAddresses)
                }
            is AppResult.Failure -> _state.update { it.copy(isLoading = false, errorMessage = result.error.message) }
        }
    }

    /** True when the server took it (the caller clears the field); the same answer for any address. */
    suspend fun add(email: String): Boolean = mutate(ADDED) { repository.add(email.trim()) }

    suspend fun remove(email: String): Boolean = mutate(REMOVED) { repository.remove(email.trim()) }

    suspend fun removeAll(): Boolean = mutate(REMOVED_ALL) { repository.removeAll() }

    fun clearMessages() {
        _state.update { it.copy(confirmation = null, errorMessage = null) }
    }

    private suspend fun mutate(
        confirmation: String,
        call: suspend () -> AppResult<Unit>,
    ): Boolean {
        if (state.value.isSaving) return false
        _state.update { it.copy(isSaving = true, confirmation = null, errorMessage = null) }
        return when (val result = call()) {
            is AppResult.Success -> {
                val summary = repository.loadSummary()
                _state.update { current ->
                    val refreshed = (summary as? AppResult.Success)?.value
                    current.copy(
                        isSaving = false,
                        confirmation = confirmation,
                        count = refreshed?.count ?: current.count,
                        maxAddresses = refreshed?.maxAddresses ?: current.maxAddresses,
                    )
                }
                true
            }
            is AppResult.Failure -> {
                _state.update { it.copy(isSaving = false, errorMessage = result.error.message) }
                false
            }
        }
    }

    private companion object {
        const val ADDED = "Added."
        const val REMOVED = "If that address was on your list, it's been removed."
        const val REMOVED_ALL = "All addresses removed."
    }
}
