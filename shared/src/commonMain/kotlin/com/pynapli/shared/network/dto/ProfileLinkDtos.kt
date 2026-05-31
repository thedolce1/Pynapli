package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

object ProfileLinkStatus {
    const val PENDING = "pending"
    const val ACTIVE = "active"
    const val DECLINED = "declined"
    const val UNLINKED = "unlinked"
}

object ProfileLinkDirection {
    const val CURRENT = "current"
    const val INCOMING = "incoming"
    const val OUTGOING = "outgoing"
}

@Serializable
data class CreateProfileLinkInvitationRequestDto(
    val targetUserId: String,
)

@Serializable
data class ProfileLinkDto(
    val linkId: String,
    val otherUserId: String,
    val otherDisplayName: String? = null,
    val otherPhotoUrl: String? = null,
    val status: String,
    val direction: String,
    val createdAt: String,
    val acceptedAt: String? = null,
    val unlinkedAt: String? = null,
)

@Serializable
data class ProfileLinksSummaryDto(
    val current: ProfileLinkDto? = null,
    val incomingInvites: List<ProfileLinkDto> = emptyList(),
    val outgoingInvites: List<ProfileLinkDto> = emptyList(),
)
