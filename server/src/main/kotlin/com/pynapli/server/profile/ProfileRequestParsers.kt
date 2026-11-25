package com.pynapli.server.profile

import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.profile.DiscoveryTagTaxonomies
import com.pynapli.shared.profile.ProfileTaxonomies
import java.time.Clock
import java.time.LocalDate
import java.time.Period

internal fun UpdateDiscoverySettingsRequestDto.toDiscoverySettingsUpdate(): DiscoverySettingsUpdate {
    val normalizedGridMessagePolicy = gridMessagePolicy?.trim()?.lowercase()
    if (normalizedGridMessagePolicy != null && normalizedGridMessagePolicy !in GRID_MESSAGE_POLICIES) {
        throw ProfileException("INVALID_GRID_MESSAGE_POLICY")
    }

    val normalizedPartnerVisibility = partnerVisibility?.trim()?.lowercase()
    if (normalizedPartnerVisibility != null && normalizedPartnerVisibility !in PARTNER_VISIBILITIES) {
        throw ProfileException("INVALID_PARTNER_VISIBILITY")
    }

    return DiscoverySettingsUpdate(
        gridVisible = gridVisible,
        swipeVisible = swipeVisible,
        showDistance = showDistance,
        showInViewedLists = showInViewedLists,
        allowGridMessages = allowGridMessages,
        gridMessagePolicy = normalizedGridMessagePolicy,
        allowFlirts = allowFlirts,
        allowIntroNotes = allowIntroNotes,
        allowAlbumRequests = allowAlbumRequests,
        visibleWhileTraveling = visibleWhileTraveling,
        sharePsyncSoloMatches = sharePsyncSoloMatches,
        partnerVisibility = normalizedPartnerVisibility,
    )
}

/**
 * Fills the fields a request left out with what is stored, so a write that names one field cannot
 * wipe the others. A name and a birthdate can be changed but never cleared; for the optional fields
 * an empty string is the explicit clear. A detail is carried over only while its key is unchanged.
 *
 * A stored key the taxonomy no longer has (or a self-describe key with no label) is not carried
 * over: it would fail validation and block a save that never touched it. It is dropped instead,
 * which is what the editor already does when it opens such a profile.
 */
internal fun UpdateProfileRequestDto.keepingUnsent(current: ProfileRecord): UpdateProfileRequestDto =
    copy(
        displayName = displayName?.takeIf { it.isNotBlank() } ?: current.displayName,
        birthdate = birthdate?.takeIf { it.isNotBlank() } ?: current.birthdate?.toString(),
        gender = gender ?: storedKey(current.gender, current.genderDetail, ProfileTaxonomies.genderKeys),
        genderDetail = genderDetail ?: current.genderDetail.takeIf { gender == null },
        orientation = orientation ?: storedKey(current.orientation, current.orientationDetail, ProfileTaxonomies.orientationKeys),
        orientationDetail = orientationDetail ?: current.orientationDetail.takeIf { orientation == null },
        relationshipContext = relationshipContext ?: current.relationshipContext,
        discoveryStyle = discoveryStyle ?: current.discoveryStyle,
    )

private fun storedKey(
    key: String?,
    detail: String?,
    allowed: Set<String>,
): String? = key?.takeIf { it in allowed && (it != ProfileTaxonomies.SELF_DESCRIBE || !detail.isNullOrBlank()) }

/**
 * Names a member can't choose: the "New member" placeholder shown for a profile without a name (a
 * member using it would look like a fake or unfinished account), and names that pass for Pynapli
 * staff. Compared case-insensitively with whitespace collapsed.
 */
internal val RESERVED_DISPLAY_NAMES =
    setOf(
        "new member",
        "pynapli",
        "pynapli user",
        "pynapli team",
        "pynapli support",
        "pynapli admin",
        "pynapli moderator",
        "admin",
        "moderator",
        "support",
    )

internal fun String.isReservedDisplayName(): Boolean = trim().lowercase().split(Regex("\\s+")).joinToString(" ") in RESERVED_DISPLAY_NAMES

internal fun UpdateProfileRequestDto.toProfileUpdate(clock: Clock): ProfileUpdate {
    val trimmedName = displayName?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmedName != null && trimmedName.length !in 2..32) {
        throw ProfileException("INVALID_DISPLAY_NAME")
    }
    if (trimmedName != null && trimmedName.isReservedDisplayName()) {
        throw ProfileException("DISPLAY_NAME_RESERVED")
    }

    val parsedBirthdate =
        birthdate?.let {
            runCatching { LocalDate.parse(it) }.getOrElse { throw ProfileException("INVALID_BIRTHDATE") }
        }
    val displayAge =
        parsedBirthdate?.let { birthdate ->
            val age = Period.between(birthdate, LocalDate.now(clock)).years
            if (age < 18) throw ProfileException("AGE_RESTRICTED")
            if (age > 120) throw ProfileException("INVALID_BIRTHDATE")
            age
        }

    val normalizedDiscoveryStyle = discoveryStyle?.trim()?.lowercase()
    if (normalizedDiscoveryStyle != null && normalizedDiscoveryStyle !in DISCOVERY_STYLES) {
        throw ProfileException("INVALID_DISCOVERY_STYLE")
    }

    val normalizedGender = gender.cleanOptional(40)
    if (normalizedGender != null && normalizedGender !in ProfileTaxonomies.genderKeys) {
        throw ProfileException("INVALID_GENDER")
    }
    val cleanGenderDetail = selfDescribeDetail(normalizedGender, genderDetail, "INVALID_GENDER")

    val normalizedOrientation = orientation.cleanOptional(40)
    if (normalizedOrientation != null && normalizedOrientation !in ProfileTaxonomies.orientationKeys) {
        throw ProfileException("INVALID_ORIENTATION")
    }
    val cleanOrientationDetail = selfDescribeDetail(normalizedOrientation, orientationDetail, "INVALID_ORIENTATION")

    val bioProvided = bio != null
    val cleanBio = bio?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanBio != null && cleanBio.length > ProfileTaxonomies.BIO_MAX_LENGTH) {
        throw ProfileException("INVALID_BIO")
    }

    val neighborhoodProvided = neighborhood != null
    val cleanNeighborhood = neighborhood?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanNeighborhood != null && cleanNeighborhood.length > ProfileTaxonomies.NEIGHBORHOOD_MAX_LENGTH) {
        throw ProfileException("INVALID_NEIGHBORHOOD")
    }

    val lifestyle = parseLifestyle(lifestyleTags, experienceLevel)

    return ProfileUpdate(
        displayName = trimmedName,
        birthdate = parsedBirthdate,
        gender = normalizedGender,
        genderDetail = cleanGenderDetail,
        orientation = normalizedOrientation,
        orientationDetail = cleanOrientationDetail,
        relationshipContext = relationshipContext.cleanOptional(40),
        discoveryStyle = normalizedDiscoveryStyle,
        displayAge = displayAge,
        profileCompletenessScore =
            profileCompleteness(
                displayName = trimmedName,
                birthdate = parsedBirthdate,
                gender = gender,
                orientation = orientation,
                relationshipContext = relationshipContext,
                discoveryStyle = normalizedDiscoveryStyle,
            ),
        bioProvided = bioProvided,
        bio = cleanBio,
        neighborhoodProvided = neighborhoodProvided,
        neighborhood = cleanNeighborhood,
        lifestyleTags = lifestyle.tags,
        experienceLevelProvided = lifestyle.experienceProvided,
        experienceLevel = lifestyle.experienceLevel,
        boundaries = boundaries.cleanBoundaries(),
        logisticsTags = logisticsTags.cleanTags(DiscoveryTagTaxonomies.logisticsTags),
        intents = intents.cleanTags(DiscoveryTagTaxonomies.intents),
    )
}
