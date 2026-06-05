package com.pynapli.app.profilehub

import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto

internal fun ProfileDto.toDraft(): ProfileEditDraft =
    ProfileEditDraft(
        displayName = displayName.orEmpty(),
        birthdate = birthdate.orEmpty(),
        gender = gender.orEmpty(),
        orientation = orientation.orEmpty(),
        relationshipContext = relationshipContext.orEmpty(),
        discoveryStyle = discoveryStyle.ifBlank { "both" },
    )

internal fun ProfileEditDraft.toRequest(): UpdateProfileRequestDto =
    UpdateProfileRequestDto(
        displayName = displayName.trim().emptyToNull(),
        birthdate = birthdate.trim().emptyToNull(),
        gender = gender.trim().emptyToNull(),
        orientation = orientation.trim().emptyToNull(),
        relationshipContext = relationshipContext.trim().emptyToNull(),
        discoveryStyle = discoveryStyle.trim().lowercase().emptyToNull(),
    )

internal fun DiscoverySettingsDto.toPrivacyDraft(): PrivacySettingsDraft =
    PrivacySettingsDraft(
        gridVisible = gridVisible,
        swipeVisible = swipeVisible,
        showDistance = showDistance,
        visibleWhileTraveling = visibleWhileTraveling,
        sharePsyncSoloMatches = sharePsyncSoloMatches,
    )

internal fun DiscoverySettingsDto.toActivityDraft(): ActivitySettingsDraft =
    ActivitySettingsDraft(
        showInViewedLists = showInViewedLists,
        allowGridMessages = allowGridMessages && gridMessagePolicy != "no_one",
        gridMessagePolicy = gridMessagePolicy.takeIf { it in gridMessagePolicies } ?: "everyone",
        allowFlirts = allowFlirts,
        allowIntroNotes = allowIntroNotes,
        allowAlbumRequests = allowAlbumRequests,
    )

internal fun PrivacySettingsDraft.toRequest(): UpdateDiscoverySettingsRequestDto =
    UpdateDiscoverySettingsRequestDto(
        gridVisible = gridVisible,
        swipeVisible = swipeVisible,
        showDistance = showDistance,
        visibleWhileTraveling = visibleWhileTraveling,
        sharePsyncSoloMatches = sharePsyncSoloMatches,
    )

internal fun ActivitySettingsDraft.toRequest(): UpdateDiscoverySettingsRequestDto =
    UpdateDiscoverySettingsRequestDto(
        showInViewedLists = showInViewedLists,
        allowGridMessages = allowGridMessages,
        gridMessagePolicy = if (allowGridMessages) gridMessagePolicy else "no_one",
        allowFlirts = allowFlirts,
        allowIntroNotes = allowIntroNotes,
        allowAlbumRequests = allowAlbumRequests,
    )

internal fun ProfileEditDraft.validationError(): String? {
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

private fun String.emptyToNull(): String? = takeIf { it.isNotEmpty() }

private val birthdatePattern = Regex("""\d{4}-\d{2}-\d{2}""")

private val discoveryStyles = setOf("solo", "together", "both", "social", "ask_in_chat")

internal val gridMessagePolicies = setOf("everyone", "matches_only", "no_one")
