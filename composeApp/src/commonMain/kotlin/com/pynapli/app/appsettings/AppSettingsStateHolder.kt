package com.pynapli.app.appsettings

import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AppSettingsStateHolder(
    private val repository: AppSettingsRepository,
) {
    private val _state = MutableStateFlow(AppSettingsUiState())
    val state: StateFlow<AppSettingsUiState> = _state.asStateFlow()

    suspend fun load() {
        if (state.value.isLoading) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        runCatching { repository.load() }
            .onSuccess { settings ->
                _state.update {
                    it.copy(
                        settings = settings,
                        draft = settings.toDraft(),
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not load app settings.",
                    )
                }
            }
    }

    fun editFromCurrent() {
        _state.update {
            it.copy(
                draft = it.settings.toDraft(),
                errorMessage = null,
            )
        }
    }

    fun updateThemeMode(value: String) = updateDraft { it.copy(themeMode = value.normalizedThemeMode()) }

    fun updateDistanceUnit(value: String) = updateDraft { it.copy(distanceUnit = value.normalizedDistanceUnit()) }

    fun updateNotificationDetail(value: String) = updateDraft { it.copy(notificationDetail = value.normalizedNotificationDetail()) }

    fun updateMessageNotifications(value: Boolean) = updateDraft { it.copy(messageNotifications = value) }

    fun updateMatchNotifications(value: Boolean) = updateDraft { it.copy(matchNotifications = value) }

    fun updateActivityNotifications(value: Boolean) = updateDraft { it.copy(activityNotifications = value) }

    fun updateQuietHoursEnabled(value: Boolean) = updateDraft { it.copy(quietHoursEnabled = value) }

    fun updateQuietHoursWindow(
        startMinute: Int,
        endMinute: Int,
    ) = updateDraft {
        it.copy(
            quietHoursStartMinute = startMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
            quietHoursEndMinute = endMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE),
        )
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    suspend fun saveDraft(): Boolean {
        val current = state.value
        if (current.isSaving) return false

        _state.update { it.copy(isSaving = true, errorMessage = null) }

        return runCatching {
            val settings = current.draft.toSettings()
            repository.save(settings)
            settings
        }.fold(
            onSuccess = { settings ->
                _state.update {
                    it.copy(
                        settings = settings,
                        draft = settings.toDraft(),
                        isSaving = false,
                        errorMessage = null,
                    )
                }
                true
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.message ?: "Could not save app settings.",
                    )
                }
                false
            },
        )
    }

    private fun updateDraft(transform: (AppSettingsDraft) -> AppSettingsDraft) {
        _state.update {
            it.copy(
                draft = transform(it.draft),
                errorMessage = null,
            )
        }
    }
}
