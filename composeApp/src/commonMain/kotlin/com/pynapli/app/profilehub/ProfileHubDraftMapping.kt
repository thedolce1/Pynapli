package com.pynapli.app.profilehub

import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.profile.ProfileTaxonomies

internal fun ProfileDto.toDraft(): ProfileEditDraft =
    ProfileEditDraft(
        displayName = displayName.orEmpty(),
        birthdate = birthdate.orEmpty(),
        bio = bio.orEmpty(),
        // Pre-taxonomy profiles may hold free text here; only structured keys map
        // onto the chips, anything else starts unset rather than rendering a bogus chip.
        gender = gender.orEmpty().takeIf { it in ProfileTaxonomies.genderKeys }.orEmpty(),
        genderDetail = genderDetail.orEmpty(),
        orientation = orientation.orEmpty().takeIf { it in ProfileTaxonomies.orientationKeys }.orEmpty(),
        orientationDetail = orientationDetail.orEmpty(),
        relationshipContext = relationshipContext.orEmpty(),
        discoveryStyle = discoveryStyle.ifBlank { "both" },
        lifestyleTags = lifestyleTags.toSet(),
        logisticsTags = logisticsTags.toSet(),
        intents = intents.toSet(),
    )

internal fun ProfileEditDraft.toRequest(): UpdateProfileRequestDto =
    UpdateProfileRequestDto(
        displayName = displayName.trim().emptyToNull(),
        birthdate = birthdate.trim().emptyToNull(),
        gender = gender.trim().emptyToNull(),
        genderDetail = genderDetail.trim().emptyToNull(),
        orientation = orientation.trim().emptyToNull(),
        orientationDetail = orientationDetail.trim().emptyToNull(),
        relationshipContext = relationshipContext.trim().emptyToNull(),
        discoveryStyle = discoveryStyle.trim().lowercase().emptyToNull(),
        // Always sent: an empty bio/tag selection clears the stored value (the
        // request's null-means-unchanged semantics are for older callers).
        bio = bio.trim(),
        lifestyleTags = lifestyleTags.toList(),
        logisticsTags = logisticsTags.toList(),
        intents = intents.toList(),
    )

internal fun DiscoverySettingsDto.toPrivacyDraft(): PrivacySettingsDraft =
    PrivacySettingsDraft(
        gridVisible = gridVisible,
        swipeVisible = swipeVisible,
        showDistance = showDistance,
        visibleWhileTraveling = visibleWhileTraveling,
        sharePsyncSoloMatches = sharePsyncSoloMatches,
        partnerVisibility = partnerVisibility.takeIf { it in partnerVisibilities } ?: "public",
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
        partnerVisibility = partnerVisibility,
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

    if (bio.trim().length > ProfileTaxonomies.BIO_MAX_LENGTH) {
        return "Bio can be up to ${ProfileTaxonomies.BIO_MAX_LENGTH} characters."
    }

    selfDescribeError(gender, genderDetail, "gender")?.let { return it }
    selfDescribeError(orientation, orientationDetail, "orientation")?.let { return it }

    if (discoveryStyle.trim().lowercase() !in discoveryStyles) {
        return "Choose a discovery style."
    }

    return null
}

private fun selfDescribeError(
    key: String,
    detail: String,
    fieldName: String,
): String? {
    if (key != ProfileTaxonomies.SELF_DESCRIBE) return null
    val cleaned = detail.trim()
    if (cleaned.isEmpty()) return "Add a short label for your self-described $fieldName."
    if (cleaned.length > ProfileTaxonomies.SELF_DESCRIBE_MAX_LENGTH) {
        return "Self-described $fieldName can be up to ${ProfileTaxonomies.SELF_DESCRIBE_MAX_LENGTH} characters."
    }
    return null
}

private fun String.emptyToNull(): String? = takeIf { it.isNotEmpty() }

private val birthdatePattern = Regex("""\d{4}-\d{2}-\d{2}""")

private val discoveryStyles = setOf("solo", "together", "both", "social", "ask_in_chat")

internal val gridMessagePolicies = setOf("everyone", "matches_only", "no_one")

internal val partnerVisibilities = setOf("public", "matches_only", "verified_only", "hidden")
