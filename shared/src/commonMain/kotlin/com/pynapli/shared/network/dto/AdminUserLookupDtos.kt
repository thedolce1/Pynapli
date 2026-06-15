package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * Consolidated admin moderation view of one user (ADMIN_MODERATION_CONSOLE_SPEC "User Lookup").
 * Deliberately excludes by-default-sensitive data — private album media, message bodies, exact
 * location, and raw verification data are never included here; those require an explicit reveal flow.
 */
@Serializable
data class AdminUserLookupDto(
    val userId: String,
    val accountStatus: String,
    val displayName: String? = null,
    val displayAge: Int? = null,
    val moderationStatus: String,
    val visibilityStatus: String,
    val ageVerified: Boolean,
    val subscriptionTier: String,
    val subscriptionSource: String,
    val linkedPartnerUserId: String? = null,
    val partnerLinked: Boolean,
    val riskTier: String,
    val riskScore: Int,
    val lastReviewedAt: String? = null,
    val recentRiskReasons: List<AdminUserRiskReasonDto> = emptyList(),
    val reportsAgainstCount: Int,
    val reportsByCount: Int,
    val blocksAgainstCount: Int,
    val blocksByCount: Int,
    val activeSessionCount: Int,
    val activeDeviceCount: Int,
    val recentModerationActions: List<AdminUserModerationActionDto> = emptyList(),
)

@Serializable
data class AdminUserRiskReasonDto(
    val source: String,
    val reason: String,
    val delta: Int,
    val occurredAt: String,
)

@Serializable
data class AdminUserModerationActionDto(
    val action: String,
    val targetType: String,
    val reason: String? = null,
    val actorId: String,
    val occurredAt: String,
)
