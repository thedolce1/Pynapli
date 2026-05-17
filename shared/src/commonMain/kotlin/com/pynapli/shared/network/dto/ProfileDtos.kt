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
    val displayName: String? = null,
    val birthdate: String? = null,
    val displayAge: Int? = null,
    val gender: String? = null,
    val orientation: String? = null,
    val relationshipContext: String? = null,
    val discoveryStyle: String,
    val profileCompletenessScore: Int,
    val moderationStatus: String,
    val visibilityStatus: String,
    val ageVerified: Boolean = false
)

@Serializable
data class PublicProfileDto(
    val userId: String,
    val displayName: String,
    val displayAge: Int? = null,
    val gender: String? = null,
    val orientation: String? = null,
    val relationshipContext: String? = null,
    val discoveryStyle: String,
    val distanceLabel: String? = null,
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
    val socialEnabled: Boolean,
    // PROFILE_SETTINGS_SPEC L221-245: when false, the viewer is hidden from
    // every discovery surface while they have an active travel_locations row.
    // Defaults true so existing clients see the documented passport behavior.
    val visibleWhileTraveling: Boolean = true
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
    val allowAlbumRequests: Boolean? = null,
    val visibleWhileTraveling: Boolean? = null
)
