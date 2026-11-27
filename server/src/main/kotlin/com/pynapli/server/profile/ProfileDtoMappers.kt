package com.pynapli.server.profile

import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaImageSet
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.PublicProfileDto

internal fun MeRecord.toDto(): MeDto =
    MeDto(
        user = AuthUserDto(userId = userId.toString(), status = userStatus),
        profile = profile.toDto(),
        discoverySettings = discoverySettings.toDto(),
    )

internal fun ProfileRecord.toDto(): ProfileDto =
    ProfileDto(
        userId = userId.toString(),
        displayName = displayName,
        birthdate = birthdate?.toString(),
        displayAge = displayAge,
        bio = bio,
        neighborhood = neighborhood,
        gender = gender,
        genderDetail = genderDetail,
        orientation = orientation,
        orientationDetail = orientationDetail,
        relationshipContext = relationshipContext,
        discoveryStyle = discoveryStyle,
        lifestyleTags = lifestyleTags,
        experienceLevel = experienceLevel,
        boundaries = boundaries,
        logisticsTags = logisticsTags,
        intents = intents,
        profileCompletenessScore = profileCompletenessScore,
        moderationStatus = moderationStatus,
        visibilityStatus = visibilityStatus,
        ageVerified = ageVerified,
    )

internal fun PublicProfileRecord.toDto(
    mediaAssetUrlResolver: MediaAssetUrlResolver?,
    // G2-2: originals with stable keys for the pager; the partner mini as a Grid thumbnail.
    images: MediaImageSet = MediaImageSet.originalsOnly(mediaAssetUrlResolver),
): PublicProfileDto {
    val first = images.original(profilePhotoMediaAssetId)
    val photos = profilePhotoMediaAssetIds.mapNotNull { images.original(it) }
    val partnerTile = images.tile(linkedPartnerThumbnailMediaAssetId)
    return PublicProfileDto(
        userId = userId.toString(),
        displayName = displayName,
        displayAge = displayAge,
        bio = bio,
        neighborhood = neighborhood,
        gender = gender,
        genderDetail = genderDetail,
        orientation = orientation,
        orientationDetail = orientationDetail,
        relationshipContext = relationshipContext,
        discoveryStyle = discoveryStyle,
        lifestyleTags = lifestyleTags,
        experienceLevel = experienceLevel,
        boundaries = boundaries,
        logisticsTags = logisticsTags,
        intents = intents,
        distanceLabel = distanceMeters?.toPublicDistanceLabel(),
        distanceMeters = distanceMeters?.toPublicDistanceMeters(),
        profilePhotoUrl = first?.url,
        photoUrls = photos.map { it.url },
        profilePhotoCacheKey = first?.cacheKey,
        photoCacheKeys = photos.map { it.cacheKey },
        profilePhotoThumbnailCacheKey = images.thumbnailCacheKey(profilePhotoMediaAssetId),
        verified = verified,
        coupleVerified = coupleVerified && linkedPartnerUserId != null,
        metInPerson = metInPerson,
        metInPersonAsCouple = metInPersonAsCouple && linkedPartnerUserId != null,
        privateAlbumAccessStatus = privateAlbumAccessStatus,
        linkedPartner =
            linkedPartnerUserId?.let { partnerUserId ->
                LinkedPartnerMiniDto(
                    userId = partnerUserId.toString(),
                    displayName = linkedPartnerDisplayName,
                    thumbnailUrl = partnerTile?.url,
                    thumbnailCacheKey = partnerTile?.cacheKey,
                    orientation = linkedPartnerOrientation,
                    orientationDetail = linkedPartnerOrientationDetail,
                    experienceLevel = linkedPartnerExperienceLevel,
                    lifestyleTags = linkedPartnerLifestyleTags,
                    age = linkedPartnerAge,
                    gender = linkedPartnerGender,
                )
            },
        allowGridMessages = allowGridMessages,
        messagingUnavailableReason = messagingUnavailableReason(),
        allowFlirts = allowFlirts,
        allowAlbumRequests = allowAlbumRequests,
    )
}

/**
 * Plan G12: why Grid messaging is off for this viewer, when telling them helps and reveals nothing.
 * Only the verification gate has a reason (the viewer can fix it); every other "no" (the owner's
 * matches-only / no-one / off settings) stays a plain [PublicProfileRecord.allowGridMessages] false.
 * Blocks and hides never reach here: the profile is not found at all.
 */
internal fun PublicProfileRecord.messagingUnavailableReason(): String? = if (!allowGridMessages && gridMessageVerifiedOnly) MESSAGING_UNAVAILABLE_VERIFY_TO_MESSAGE else null

internal const val MESSAGING_UNAVAILABLE_VERIFY_TO_MESSAGE = "VERIFY_TO_MESSAGE"

internal fun DiscoverySettingsRecord.toDto(): DiscoverySettingsDto =
    DiscoverySettingsDto(
        gridVisible = gridVisible,
        swipeVisible = swipeVisible,
        eventsVisible = eventsVisible,
        showDistance = showDistance,
        showInViewedLists = showInViewedLists,
        incognito = incognito,
        allowGridMessages = allowGridMessages,
        gridMessagePolicy = gridMessagePolicy,
        allowFlirts = allowFlirts,
        allowIntroNotes = allowIntroNotes,
        allowAlbumRequests = allowAlbumRequests,
        soloEnabled = soloEnabled,
        togetherEnabled = togetherEnabled,
        socialEnabled = socialEnabled,
        visibleWhileTraveling = visibleWhileTraveling,
        sharePsyncSoloMatches = sharePsyncSoloMatches,
        partnerVisibility = partnerVisibility,
        showMetInPersonBadge = showMetInPersonBadge,
    )
