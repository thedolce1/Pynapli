package com.pynapli.server.admin

import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.profile.ProfileRepository
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import java.util.UUID

/**
 * Builds the consolidated admin user-lookup view by composing the profile/status, subscription
 * entitlement, and partner-link reads that already own that logic, plus cross-cutting moderation
 * facts. Every successful lookup is audited (the view exposes a real user's account state), and no
 * by-default-sensitive content (album media, message bodies, exact location, raw verification) is
 * included.
 */
class AdminUserLookupService(
    private val profileRepository: ProfileRepository,
    private val repository: AdminUserLookupRepository,
    private val entitlementLookup: suspend (UUID) -> SubscriptionEntitlementDto,
    private val partnerLinkLookup: suspend (UUID) -> ProfileLinksSummaryDto,
) {
    suspend fun lookup(
        actorId: String,
        userId: UUID,
        reason: String,
    ): AdminUserLookupDto {
        val me = profileRepository.getMe(userId) ?: throw ModerationException("USER_NOT_FOUND")
        val entitlement = entitlementLookup(userId)
        val partner = partnerLinkLookup(userId)
        val facts = repository.moderationFacts(userId)

        // Audit only after the user is confirmed to exist — this records a real data reveal.
        repository.recordLookupAudit(actorId = actorId, userId = userId, reason = reason)

        return AdminUserLookupDto(
            userId = userId.toString(),
            accountStatus = me.userStatus,
            displayName = me.profile.displayName,
            displayAge = me.profile.displayAge,
            moderationStatus = me.profile.moderationStatus,
            visibilityStatus = me.profile.visibilityStatus,
            ageVerified = me.profile.ageVerified,
            subscriptionTier = entitlement.tier,
            subscriptionSource = entitlement.source,
            linkedPartnerUserId = partner.current?.otherUserId,
            partnerLinked = partner.current != null,
            riskTier = facts.riskTier,
            riskScore = facts.riskScore,
            lastReviewedAt = facts.lastReviewedAt?.toString(),
            recentRiskReasons =
                facts.recentRiskReasons.map {
                    AdminUserRiskReasonDto(
                        source = it.source,
                        reason = it.reason,
                        delta = it.delta,
                        occurredAt = it.occurredAt.toString(),
                    )
                },
            reportsAgainstCount = facts.reportsAgainstCount,
            reportsByCount = facts.reportsByCount,
            blocksAgainstCount = facts.blocksAgainstCount,
            blocksByCount = facts.blocksByCount,
            activeSessionCount = facts.activeSessionCount,
            activeDeviceCount = facts.activeDeviceCount,
            recentModerationActions =
                facts.recentModerationActions.map {
                    AdminUserModerationActionDto(
                        action = it.action,
                        targetType = it.targetType,
                        reason = it.reason,
                        actorId = it.actorId,
                        occurredAt = it.occurredAt.toString(),
                    )
                },
        )
    }
}
