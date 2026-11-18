package com.pynapli.app.profilehub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ProfileHubDraftActions(
    private val state: MutableStateFlow<ProfileHubUiState>,
) {
    fun updateDisplayName(value: String) = updateDraft { it.copy(displayName = value) }

    fun updateBirthdate(value: String) = updateDraft { it.copy(birthdate = value) }

    // Gender/orientation are chip-driven structured keys: tapping the selected chip
    // clears the field (both are optional).
    fun updateGender(value: String) = updateDraft { it.copy(gender = if (it.gender == value) "" else value) }

    fun updateGenderDetail(value: String) = updateDraft { it.copy(genderDetail = value) }

    fun updateOrientation(value: String) = updateDraft { it.copy(orientation = if (it.orientation == value) "" else value) }

    fun updateOrientationDetail(value: String) = updateDraft { it.copy(orientationDetail = value) }

    fun updateBio(value: String) = updateDraft { it.copy(bio = value) }

    fun updateNeighborhood(value: String) = updateDraft { it.copy(neighborhood = value) }

    fun toggleLifestyleTag(value: String) = updateDraft { it.copy(lifestyleTags = it.lifestyleTags.toggled(value)) }

    // One level per person: tapping the selected level clears it.
    fun updateExperienceLevel(value: String) = updateDraft { it.copy(experienceLevel = if (it.experienceLevel == value) "" else value) }

    // At most one stance per boundary item; tapping the selected stance clears the item.
    fun updateBoundaryStance(
        item: String,
        stance: String,
    ) = updateDraft { draft ->
        val token = "$item:$stance"
        val others = draft.boundaries.filterNot { it.substringBefore(':') == item }.toSet()
        draft.copy(boundaries = if (token in draft.boundaries) others else others + token)
    }

    fun toggleLogisticsTag(value: String) = updateDraft { it.copy(logisticsTags = it.logisticsTags.toggled(value)) }

    fun toggleIntent(value: String) = updateDraft { it.copy(intents = it.intents.toggled(value)) }

    fun updateRelationshipContext(value: String) = updateDraft { it.copy(relationshipContext = value) }

    fun updateDiscoveryStyle(value: String) = updateDraft { it.copy(discoveryStyle = value) }

    fun updatePartnerVisibility(value: String) = updatePrivacyDraft { it.copy(partnerVisibility = value) }

    fun updateGridVisible(value: Boolean) = updatePrivacyDraft { it.copy(gridVisible = value) }

    fun updateSwipeVisible(value: Boolean) = updatePrivacyDraft { it.copy(swipeVisible = value) }

    fun updateShowDistance(value: Boolean) = updatePrivacyDraft { it.copy(showDistance = value) }

    fun updateVisibleWhileTraveling(value: Boolean) = updatePrivacyDraft { it.copy(visibleWhileTraveling = value) }

    fun updateSharePsyncSoloMatches(value: Boolean) = updatePrivacyDraft { it.copy(sharePsyncSoloMatches = value) }

    fun updateShowInViewedLists(value: Boolean) = updateActivityDraft { it.copy(showInViewedLists = value) }

    fun updateAllowGridMessages(value: Boolean) =
        updateActivityDraft {
            it.copy(
                allowGridMessages = value,
                gridMessagePolicy =
                    when {
                        value && it.gridMessagePolicy == "no_one" -> "everyone"
                        !value -> "no_one"
                        else -> it.gridMessagePolicy
                    },
            )
        }

    fun updateGridMessagePolicy(value: String) =
        updateActivityDraft {
            val normalized = value.lowercase()
            it.copy(
                gridMessagePolicy = normalized,
                allowGridMessages = normalized != "no_one",
            )
        }

    fun updateAllowFlirts(value: Boolean) = updateActivityDraft { it.copy(allowFlirts = value) }

    fun updateAllowIntroNotes(value: Boolean) = updateActivityDraft { it.copy(allowIntroNotes = value) }

    fun updateAllowAlbumRequests(value: Boolean) = updateActivityDraft { it.copy(allowAlbumRequests = value) }

    private fun updateDraft(transform: (ProfileEditDraft) -> ProfileEditDraft) {
        state.update {
            it.copy(
                draft = transform(it.draft),
                errorMessage = null,
                feedbackMessage = null,
            )
        }
    }

    private fun updatePrivacyDraft(transform: (PrivacySettingsDraft) -> PrivacySettingsDraft) {
        state.update {
            it.copy(
                privacyDraft = transform(it.privacyDraft),
                errorMessage = null,
                feedbackMessage = null,
            )
        }
    }

    private fun updateActivityDraft(transform: (ActivitySettingsDraft) -> ActivitySettingsDraft) {
        state.update {
            it.copy(
                activityDraft = transform(it.activityDraft),
                errorMessage = null,
                feedbackMessage = null,
            )
        }
    }

    private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value
}
