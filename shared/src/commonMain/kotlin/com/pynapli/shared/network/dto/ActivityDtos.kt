package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ActivityFeedResponseDto(
    val items: List<ActivityEventDto>,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val entitlementTier: String? = null,
    val revealLimit: Int? = null,
    val gatedCount: Int = 0
)

@Serializable
data class ActivitySummaryDto(
    val badgeCount: Int,
    val profileViewCount: Int = 0,
    val flirtCount: Int = 0,
    val likeCount: Int = 0,
    val matchCount: Int = 0,
    val albumRequestCount: Int = 0,
    val partnerInviteCount: Int = 0,
    val latestBadgeOccurredAt: String? = null
)

@Serializable
data class MarkActivitySeenRequestDto(
    val eventIds: List<String>
)

@Serializable
data class MarkActivitySeenResponseDto(
    val markedCount: Int
)

@Serializable
data class SendFlirtRequestDto(
    val targetUserId: String
)

@Serializable
data class SendFlirtResponseDto(
    val sent: Boolean
)

@Serializable
data class RequestAlbumAccessRequestDto(
    val targetUserId: String
)

@Serializable
data class RequestAlbumAccessResponseDto(
    val requested: Boolean
)

@Serializable
data class RespondAlbumAccessRequestDto(
    val approve: Boolean
)

@Serializable
data class RespondAlbumAccessResponseDto(
    val eventId: String,
    val status: String
)

@Serializable
data class DismissActivityEventResponseDto(
    val eventId: String,
    val dismissed: Boolean
)

@Serializable
data class ActivityEventDto(
    val eventId: String,
    val type: String,
    val actorUserId: String,
    val actorDisplayName: String,
    val actorPhotoUrl: String? = null,
    val count: Int = 1,
    val occurredAt: String,
    val seen: Boolean = false,
    val distanceLabel: String? = null,
    val distanceMeters: Int? = null,
    val albumRequestStatus: String? = null,
    val matchId: String? = null,
    val conversationId: String? = null
)
