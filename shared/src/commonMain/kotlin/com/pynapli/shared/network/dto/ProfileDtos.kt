package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class MeDto(
    val user: AuthUserDto,
    val profile: ProfileDto,
    val discoverySettings: DiscoverySettingsDto
)

@Serializable
data class ProfileDto(
    val userId: String,
    val displayName: String?,
    val birthdate: String?,
    val displayAge: Int?,
    val gender: String?,
    val orientation: String?,
    val relationshipContext: String?,
    val discoveryStyle: String,
    val profileCompletenessScore: Int,
    val moderationStatus: String,
    val visibilityStatus: String
)

@Serializable
data class PublicProfileDto(
    val userId: String,
    val displayName: String,
    val displayAge: Int?,
    val gender: String?,
    val orientation: String?,
    val relationshipContext: String?,
    val discoveryStyle: String,
    val distanceLabel: String?,
    val distanceMeters: Int? = null,
    val profilePhotoUrl: String? = null,
    val privateAlbumAccessStatus: String = "none",
    val linkedPartner: LinkedPartnerMiniDto? = null,
    val allowGridMessages: Boolean,
    val allowFlirts: Boolean,
    val allowAlbumRequests: Boolean
)

@Serializable
data class DiscoverySettingsDto(
    val gridVisible: Boolean,
    val swipeVisible: Boolean,
    val eventsVisible: Boolean,
    val showDistance: Boolean,
    val showInViewedLists: Boolean,
    val incognito: Boolean,
    val allowGridMessages: Boolean,
    val gridMessagePolicy: String,
    val allowFlirts: Boolean,
    val allowIntroNotes: Boolean,
    val allowAlbumRequests: Boolean,
    val soloEnabled: Boolean,
    val togetherEnabled: Boolean,
    val socialEnabled: Boolean
)

@Serializable
data class UpdateProfileRequestDto(
    val displayName: String? = null,
    val birthdate: String? = null,
    val gender: String? = null,
    val orientation: String? = null,
    val relationshipContext: String? = null,
    val discoveryStyle: String? = null
)

@Serializable
data class UpdateDiscoverySettingsRequestDto(
    val gridVisible: Boolean? = null,
    val swipeVisible: Boolean? = null,
    val showDistance: Boolean? = null,
    val showInViewedLists: Boolean? = null,
    val allowGridMessages: Boolean? = null,
    val gridMessagePolicy: String? = null,
    val allowFlirts: Boolean? = null,
    val allowIntroNotes: Boolean? = null,
    val allowAlbumRequests: Boolean? = null
)
