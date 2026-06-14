package com.pynapli.app.profilehub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ProfileHubStateHolder(
    repository: ProfileHubRepository,
) {
    private val _state = MutableStateFlow(ProfileHubUiState())
    val state: StateFlow<ProfileHubUiState> = _state.asStateFlow()

    private val loader = ProfileHubLoader(repository, _state)
    private val panels = ProfileHubPanelActions(_state)
    private val drafts = ProfileHubDraftActions(_state)
    private val account = ProfileHubAccountActions(repository, _state)
    private val safety = ProfileHubSafetyActions(repository, _state)
    private val saves = ProfileHubSaveActions(repository, _state)

    suspend fun load() = loader.load()

    fun openEditor() = panels.openEditor()

    fun dismissEditor() = panels.dismissEditor()

    fun openPrivacySettings() = panels.openPrivacySettings()

    fun dismissPrivacySettings() = panels.dismissPrivacySettings()

    fun openPartner() = panels.openPartner()

    fun dismissPartner() = panels.dismissPartner()

    fun openActivityControls() = panels.openActivityControls()

    fun dismissActivityControls() = panels.dismissActivityControls()

    fun openAppSettings() = panels.openAppSettings()

    fun dismissAppSettings() = panels.dismissAppSettings()

    fun openNotificationCenter() = panels.openNotificationCenter()

    fun dismissNotificationCenter() = panels.dismissNotificationCenter()

    fun openAgeVerification() = panels.openAgeVerification()

    fun dismissAgeVerification() = panels.dismissAgeVerification()

    fun openSafetyCenter() = panels.openSafetyCenter()

    fun dismissSafetyCenter() = panels.dismissSafetyCenter()

    fun closeAppSettingsWithFeedback(message: String) = panels.closeAppSettingsWithFeedback(message)

    fun updateDisplayName(value: String) = drafts.updateDisplayName(value)

    fun updateBirthdate(value: String) = drafts.updateBirthdate(value)

    fun updateGender(value: String) = drafts.updateGender(value)

    fun updateGenderDetail(value: String) = drafts.updateGenderDetail(value)

    fun updateOrientation(value: String) = drafts.updateOrientation(value)

    fun updateOrientationDetail(value: String) = drafts.updateOrientationDetail(value)

    fun updateBio(value: String) = drafts.updateBio(value)

    fun updateNeighborhood(value: String) = drafts.updateNeighborhood(value)

    fun toggleLifestyleTag(value: String) = drafts.toggleLifestyleTag(value)

    fun toggleLogisticsTag(value: String) = drafts.toggleLogisticsTag(value)

    fun toggleIntent(value: String) = drafts.toggleIntent(value)

    fun updateRelationshipContext(value: String) = drafts.updateRelationshipContext(value)

    fun updateDiscoveryStyle(value: String) = drafts.updateDiscoveryStyle(value)

    fun updatePartnerVisibility(value: String) = drafts.updatePartnerVisibility(value)

    fun updateGridVisible(value: Boolean) = drafts.updateGridVisible(value)

    fun updateSwipeVisible(value: Boolean) = drafts.updateSwipeVisible(value)

    fun updateShowDistance(value: Boolean) = drafts.updateShowDistance(value)

    fun updateVisibleWhileTraveling(value: Boolean) = drafts.updateVisibleWhileTraveling(value)

    fun updateSharePsyncSoloMatches(value: Boolean) = drafts.updateSharePsyncSoloMatches(value)

    fun updateShowInViewedLists(value: Boolean) = drafts.updateShowInViewedLists(value)

    fun updateAllowGridMessages(value: Boolean) = drafts.updateAllowGridMessages(value)

    fun updateGridMessagePolicy(value: String) = drafts.updateGridMessagePolicy(value)

    fun updateAllowFlirts(value: Boolean) = drafts.updateAllowFlirts(value)

    fun updateAllowIntroNotes(value: Boolean) = drafts.updateAllowIntroNotes(value)

    fun updateAllowAlbumRequests(value: Boolean) = drafts.updateAllowAlbumRequests(value)

    fun clearMessage() {
        _state.update { it.copy(errorMessage = null, feedbackMessage = null) }
    }

    suspend fun requestDataExport() = account.requestDataExport()

    suspend fun deleteAccount(confirmation: String): Boolean = account.deleteAccount(confirmation)

    suspend fun loadBlocks() = safety.loadBlocks()

    suspend fun loadHiddenProfiles() = safety.loadHiddenProfiles()

    suspend fun loadReportHistory() = safety.loadReportHistory()

    suspend fun loadSupportContact() = safety.loadSupportContact()

    suspend fun loadSafetyLists() = safety.loadSafetyLists()

    suspend fun unblockUser(userId: String) = safety.unblockUser(userId)

    suspend fun unhideProfile(userId: String) = safety.unhideProfile(userId)

    fun reset() {
        _state.value = ProfileHubUiState()
    }

    suspend fun saveDraft() = saves.saveDraft()

    suspend fun savePrivacySettings() = saves.savePrivacySettings()

    suspend fun savePartner() = saves.savePartner()

    suspend fun saveActivityControls() = saves.saveActivityControls()

    suspend fun startAgeVerification() = saves.startAgeVerification()
}
