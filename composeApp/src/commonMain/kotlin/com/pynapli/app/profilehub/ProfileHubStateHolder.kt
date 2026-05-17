package com.pynapli.app.profilehub

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class ProfileHubUiState(
    val me: MeDto? = null,
    val draft: ProfileEditDraft = ProfileEditDraft(),
    val privacyDraft: PrivacySettingsDraft = PrivacySettingsDraft(),
    val activityDraft: ActivitySettingsDraft = ActivitySettingsDraft(),
    val editVisible: Boolean = false,
    val privacyVisible: Boolean = false,
    val activityVisible: Boolean = false,
    val appSettingsVisible: Boolean = false,
    val notificationCenterVisible: Boolean = false,
    val ageVerificationDialogVisible: Boolean = false,
    val ageVerificationStatus: AgeVerificationStatusDto? = null,
    val isAgeVerificationMutating: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null
)

@Immutable
data class ProfileEditDraft(
    val displayName: String = "",
    val birthdate: String = "",
    val gender: String = "",
    val orientation: String = "",
    val relationshipContext: String = "",
    val discoveryStyle: String = "both"
)

@Immutable
data class PrivacySettingsDraft(
    val gridVisible: Boolean = true,
    val swipeVisible: Boolean = true,
    val showDistance: Boolean = true,
    // PROFILE_SETTINGS_SPEC L221-245: when false, the viewer is hidden from
    // every discovery surface while they have an active travel_locations row.
    val visibleWhileTraveling: Boolean = true
)

@Immutable
data class ActivitySettingsDraft(
    val showInViewedLists: Boolean = true,
    val allowGridMessages: Boolean = true,
    val gridMessagePolicy: String = "everyone",
    val allowFlirts: Boolean = true,
    val allowIntroNotes: Boolean = true,
    val allowAlbumRequests: Boolean = true
)

class ProfileHubStateHolder(
    private val repository: ProfileHubRepository
) {
    private val _state = MutableStateFlow(ProfileHubUiState())
    val state: StateFlow<ProfileHubUiState> = _state.asStateFlow()

    suspend fun load() {
        if (state.value.isLoading) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        when (val result = repository.loadMe()) {
            is AppResult.Success -> {
                val ageVerificationStatus = when (val status = repository.loadAgeVerificationStatus()) {
                    is AppResult.Success -> status.value
                    is AppResult.Failure -> null
                }
                _state.update { existing ->
                    existing.copy(
                        me = result.value,
                        ageVerificationStatus = ageVerificationStatus,
                        draft = if (existing.editVisible) existing.draft else result.value.profile.toDraft(),
                        privacyDraft = if (existing.privacyVisible) {
                            existing.privacyDraft
                        } else {
                            result.value.discoverySettings.toPrivacyDraft()
                        },
                        activityDraft = if (existing.activityVisible) {
                            existing.activityDraft
                        } else {
                            result.value.discoverySettings.toActivityDraft()
                        },
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

    fun openEditor() {
        _state.update { existing ->
            existing.copy(
                editVisible = true,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                ageVerificationDialogVisible = false,
                draft = existing.me?.profile?.toDraft() ?: existing.draft,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissEditor() {
        _state.update { existing ->
            existing.copy(
                editVisible = false,
                draft = existing.me?.profile?.toDraft() ?: existing.draft,
                errorMessage = null
            )
        }
    }

    fun openPrivacySettings() {
        _state.update { existing ->
            existing.copy(
                privacyVisible = true,
                editVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                ageVerificationDialogVisible = false,
                privacyDraft = existing.me?.discoverySettings?.toPrivacyDraft() ?: existing.privacyDraft,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissPrivacySettings() {
        _state.update { existing ->
            existing.copy(
                privacyVisible = false,
                privacyDraft = existing.me?.discoverySettings?.toPrivacyDraft() ?: existing.privacyDraft,
                errorMessage = null
            )
        }
    }

    fun openActivityControls() {
        _state.update { existing ->
            existing.copy(
                activityVisible = true,
                editVisible = false,
                privacyVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                ageVerificationDialogVisible = false,
                activityDraft = existing.me?.discoverySettings?.toActivityDraft() ?: existing.activityDraft,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissActivityControls() {
        _state.update { existing ->
            existing.copy(
                activityVisible = false,
                activityDraft = existing.me?.discoverySettings?.toActivityDraft() ?: existing.activityDraft,
                errorMessage = null
            )
        }
    }

    fun openAppSettings() {
        _state.update { existing ->
            existing.copy(
                appSettingsVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                notificationCenterVisible = false,
                ageVerificationDialogVisible = false,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissAppSettings() {
        _state.update { it.copy(appSettingsVisible = false, errorMessage = null) }
    }

    fun openNotificationCenter() {
        _state.update { existing ->
            existing.copy(
                notificationCenterVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                ageVerificationDialogVisible = false,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissNotificationCenter() {
        _state.update { it.copy(notificationCenterVisible = false, errorMessage = null) }
    }

    fun openAgeVerification() {
        _state.update { existing ->
            existing.copy(
                ageVerificationDialogVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissAgeVerification() {
        _state.update { it.copy(ageVerificationDialogVisible = false, errorMessage = null) }
    }

    fun closeAppSettingsWithFeedback(message: String) {
        _state.update {
            it.copy(
                appSettingsVisible = false,
                errorMessage = null,
                feedbackMessage = message
            )
        }
    }

    fun updateDisplayName(value: String) = updateDraft { it.copy(displayName = value) }

    fun updateBirthdate(value: String) = updateDraft { it.copy(birthdate = value) }

    fun updateGender(value: String) = updateDraft { it.copy(gender = value) }

    fun updateOrientation(value: String) = updateDraft { it.copy(orientation = value) }

    fun updateRelationshipContext(value: String) = updateDraft { it.copy(relationshipContext = value) }

    fun updateDiscoveryStyle(value: String) = updateDraft { it.copy(discoveryStyle = value) }

    fun updateGridVisible(value: Boolean) = updatePrivacyDraft { it.copy(gridVisible = value) }

    fun updateSwipeVisible(value: Boolean) = updatePrivacyDraft { it.copy(swipeVisible = value) }

    fun updateShowDistance(value: Boolean) = updatePrivacyDraft { it.copy(showDistance = value) }

    fun updateVisibleWhileTraveling(value: Boolean) = updatePrivacyDraft { it.copy(visibleWhileTraveling = value) }

    fun updateShowInViewedLists(value: Boolean) = updateActivityDraft { it.copy(showInViewedLists = value) }

    fun updateAllowGridMessages(value: Boolean) = updateActivityDraft {
        it.copy(
            allowGridMessages = value,
            gridMessagePolicy = when {
                value && it.gridMessagePolicy == "no_one" -> "everyone"
                !value -> "no_one"
                else -> it.gridMessagePolicy
            }
        )
    }

    fun updateGridMessagePolicy(value: String) = updateActivityDraft {
        val normalized = value.lowercase()
        it.copy(
            gridMessagePolicy = normalized,
            allowGridMessages = normalized != "no_one"
        )
    }

    fun updateAllowFlirts(value: Boolean) = updateActivityDraft { it.copy(allowFlirts = value) }

    fun updateAllowIntroNotes(value: Boolean) = updateActivityDraft { it.copy(allowIntroNotes = value) }

    fun updateAllowAlbumRequests(value: Boolean) = updateActivityDraft { it.copy(allowAlbumRequests = value) }

    fun clearMessage() {
        _state.update { it.copy(errorMessage = null, feedbackMessage = null) }
    }

    fun reset() {
        _state.value = ProfileHubUiState()
    }

    suspend fun saveDraft() {
        val current = state.value
        if (current.isSaving) return

        val validationError = current.draft.validationError()
        if (validationError != null) {
            _state.update { it.copy(errorMessage = validationError, feedbackMessage = null) }
            return
        }

        _state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.updateProfile(current.draft.toRequest())) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        me = result.value,
                        draft = result.value.profile.toDraft(),
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        editVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Profile updated"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun savePrivacySettings() {
        val current = state.value
        if (current.isSaving) return

        _state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.updateDiscoverySettings(current.privacyDraft.toRequest())) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        me = result.value,
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        privacyVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Privacy updated"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun saveActivityControls() {
        val current = state.value
        if (current.isSaving) return

        if (current.activityDraft.gridMessagePolicy !in gridMessagePolicies) {
            _state.update { it.copy(errorMessage = "Choose who can message from the grid.", feedbackMessage = null) }
            return
        }

        _state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.updateDiscoverySettings(current.activityDraft.toRequest())) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        me = result.value,
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        activityVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Activity controls updated"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun startAgeVerification() {
        val current = state.value
        if (current.isAgeVerificationMutating) return

        _state.update {
            it.copy(
                isAgeVerificationMutating = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (
            val result = repository.startAgeVerification(
                StartAgeVerificationRequestDto(confirmedAdult = true)
            )
        ) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        ageVerificationStatus = result.value,
                        ageVerificationDialogVisible = false,
                        isAgeVerificationMutating = false,
                        errorMessage = null,
                        feedbackMessage = result.value.message
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isAgeVerificationMutating = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    private fun updateDraft(transform: (ProfileEditDraft) -> ProfileEditDraft) {
        _state.update {
            it.copy(
                draft = transform(it.draft),
                errorMessage = null,
                feedbackMessage = null
            )
        }
    }

    private fun updatePrivacyDraft(transform: (PrivacySettingsDraft) -> PrivacySettingsDraft) {
        _state.update {
            it.copy(
                privacyDraft = transform(it.privacyDraft),
                errorMessage = null,
                feedbackMessage = null
            )
        }
    }

    private fun updateActivityDraft(transform: (ActivitySettingsDraft) -> ActivitySettingsDraft) {
        _state.update {
            it.copy(
                activityDraft = transform(it.activityDraft),
                errorMessage = null,
                feedbackMessage = null
            )
        }
    }
}

interface ProfileHubRepository {
    suspend fun loadMe(): AppResult<MeDto>

    suspend fun loadAgeVerificationStatus(): AppResult<AgeVerificationStatusDto>

    suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto
    ): AppResult<AgeVerificationStatusDto>

    suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto>

    suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto>
}

class ApiProfileHubRepository(
    private val apiClient: PynapliApiClient
) : ProfileHubRepository {
    override suspend fun loadMe(): AppResult<MeDto> =
        apiClient.getMe()

    override suspend fun loadAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> =
        apiClient.getAgeVerificationStatus()

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto
    ): AppResult<AgeVerificationStatusDto> =
        apiClient.startAgeVerification(request)

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> =
        apiClient.updateProfile(request)

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> =
        apiClient.updateDiscoverySettings(request)
}

private fun ProfileDto.toDraft(): ProfileEditDraft = ProfileEditDraft(
    displayName = displayName.orEmpty(),
    birthdate = birthdate.orEmpty(),
    gender = gender.orEmpty(),
    orientation = orientation.orEmpty(),
    relationshipContext = relationshipContext.orEmpty(),
    discoveryStyle = discoveryStyle.ifBlank { "both" }
)

private fun ProfileEditDraft.toRequest(): UpdateProfileRequestDto = UpdateProfileRequestDto(
    displayName = displayName.trim().emptyToNull(),
    birthdate = birthdate.trim().emptyToNull(),
    gender = gender.trim().emptyToNull(),
    orientation = orientation.trim().emptyToNull(),
    relationshipContext = relationshipContext.trim().emptyToNull(),
    discoveryStyle = discoveryStyle.trim().lowercase().emptyToNull()
)

private fun DiscoverySettingsDto.toPrivacyDraft(): PrivacySettingsDraft = PrivacySettingsDraft(
    gridVisible = gridVisible,
    swipeVisible = swipeVisible,
    showDistance = showDistance,
    visibleWhileTraveling = visibleWhileTraveling
)

private fun DiscoverySettingsDto.toActivityDraft(): ActivitySettingsDraft = ActivitySettingsDraft(
    showInViewedLists = showInViewedLists,
    allowGridMessages = allowGridMessages && gridMessagePolicy != "no_one",
    gridMessagePolicy = gridMessagePolicy.takeIf { it in gridMessagePolicies } ?: "everyone",
    allowFlirts = allowFlirts,
    allowIntroNotes = allowIntroNotes,
    allowAlbumRequests = allowAlbumRequests
)

private fun PrivacySettingsDraft.toRequest(): UpdateDiscoverySettingsRequestDto = UpdateDiscoverySettingsRequestDto(
    gridVisible = gridVisible,
    swipeVisible = swipeVisible,
    showDistance = showDistance,
    visibleWhileTraveling = visibleWhileTraveling
)

private fun ActivitySettingsDraft.toRequest(): UpdateDiscoverySettingsRequestDto = UpdateDiscoverySettingsRequestDto(
    showInViewedLists = showInViewedLists,
    allowGridMessages = allowGridMessages,
    gridMessagePolicy = if (allowGridMessages) gridMessagePolicy else "no_one",
    allowFlirts = allowFlirts,
    allowIntroNotes = allowIntroNotes,
    allowAlbumRequests = allowAlbumRequests
)

private fun ProfileEditDraft.validationError(): String? {
    val cleanName = displayName.trim()
    if (cleanName.length !in 2..32) {
        return "Display name must be 2-32 characters."
    }

    val cleanBirthdate = birthdate.trim()
    if (cleanBirthdate.isNotEmpty() && !birthdatePattern.matches(cleanBirthdate)) {
        return "Birthdate must use YYYY-MM-DD."
    }

    if (discoveryStyle.trim().lowercase() !in discoveryStyles) {
        return "Choose a discovery style."
    }

    return null
}

private fun String.emptyToNull(): String? =
    takeIf { it.isNotEmpty() }

private val birthdatePattern = Regex("""\d{4}-\d{2}-\d{2}""")

private val discoveryStyles = setOf("solo", "together", "both", "social", "ask_in_chat")

private val gridMessagePolicies = setOf("everyone", "matches_only", "no_one")
