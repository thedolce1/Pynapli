package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * Admin partner-link review for support/abuse (ADMIN_MODERATION_CONSOLE_SPEC "Partner Link Review").
 * Read-only: shows current + past links, churn, partner visibility, and report context. Force-unlink
 * and benefit-disable actions are a separate follow-up.
 */
@Serializable
data class AdminPartnerLinkReviewDto(
    val userId: String,
    val partnerVisibility: String,
    val currentLink: AdminPartnerLinkDto? = null,
    val history: List<AdminPartnerLinkDto> = emptyList(),
    val activeLinkCount: Int,
    val pastLinkCount: Int,
    // Links unlinked within the last 30 days: a relink/subscription-cycling abuse signal.
    val recentUnlinkCount: Int,
    val reportsAgainstUserCount: Int,
    val reportsAgainstCurrentPartnerCount: Int,
)

@Serializable
data class AdminPartnerLinkDto(
    val linkId: String,
    val otherUserId: String,
    val status: String,
    // "initiated" when this user sent the link, "received" when the other user did.
    val direction: String,
    val createdAt: String,
    val acceptedAt: String? = null,
    val unlinkedAt: String? = null,
)
