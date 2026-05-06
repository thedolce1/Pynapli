package com.pynapli.server.profile

import java.time.LocalDate
import java.util.UUID

interface ProfileRepository {
    suspend fun getMe(userId: UUID): MeRecord?

    suspend fun getPublicProfile(viewerUserId: UUID, targetUserId: UUID): PublicProfileRecord?

    suspend fun updateProfile(userId: UUID, update: ProfileUpdate): MeRecord?

    suspend fun updateDiscoverySettings(userId: UUID, update: DiscoverySettingsUpdate): MeRecord?
}

data class MeRecord(
    val userId: UUID,
    val userStatus: String,
    val profile: ProfileRecord,
    val discoverySettings: DiscoverySettingsRecord
)

data class ProfileRecord(
    val userId: UUID,
    val displayName: String?,
    val birthdate: LocalDate?,
    val displayAge: Int?,
    val gender: String?,
    val orientation: String?,
    val relationshipContext: String?,
    val discoveryStyle: String,
    val profileCompletenessScore: Int,
    val moderationStatus: String,
    val visibilityStatus: String
)

data class DiscoverySettingsRecord(
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

data class PublicProfileRecord(
    val userId: UUID,
    val displayName: String,
    val profilePhotoMediaAssetId: UUID?,
    val linkedPartnerUserId: UUID? = null,
    val linkedPartnerDisplayName: String? = null,
    val linkedPartnerThumbnailMediaAssetId: UUID? = null,
    val displayAge: Int?,
    val gender: String?,
    val orientation: String?,
    val relationshipContext: String?,
    val discoveryStyle: String,
    val distanceMeters: Double?,
    val privateAlbumAccessStatus: String,
    val allowGridMessages: Boolean,
    val allowFlirts: Boolean,
    val allowAlbumRequests: Boolean
)

data class ProfileUpdate(
    val displayName: String?,
    val birthdate: LocalDate?,
    val gender: String?,
    val orientation: String?,
    val relationshipContext: String?,
    val discoveryStyle: String?,
    val displayAge: Int?,
    val profileCompletenessScore: Int
)

data class DiscoverySettingsUpdate(
    val gridVisible: Boolean?,
    val swipeVisible: Boolean?,
    val showDistance: Boolean?,
    val showInViewedLists: Boolean?,
    val allowGridMessages: Boolean?,
    val gridMessagePolicy: String?,
    val allowFlirts: Boolean?,
    val allowIntroNotes: Boolean?,
    val allowAlbumRequests: Boolean?
)
