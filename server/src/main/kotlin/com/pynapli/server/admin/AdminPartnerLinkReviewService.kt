package com.pynapli.server.admin

import com.pynapli.server.moderation.ModerationException
import com.pynapli.shared.network.dto.AdminPartnerLinkDto
import com.pynapli.shared.network.dto.AdminPartnerLinkReviewDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Builds the read-only admin partner-link review view. This intentionally exposes only link
 * metadata and report counts: force unlinking, subscription benefit changes, private media, exact
 * locations, and message bodies are separate privileged workflows.
 */
class AdminPartnerLinkReviewService(
    private val repository: AdminPartnerLinkReviewRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun review(
        actorId: String,
        userId: UUID,
        reason: String,
    ): AdminPartnerLinkReviewDto {
        val facts = repository.review(userId) ?: throw ModerationException("USER_NOT_FOUND")
        repository.recordReviewAudit(actorId = actorId, userId = userId, reason = reason)
        return facts.toDto(userId = userId, clock = clock)
    }
}

private fun AdminPartnerLinkReviewFacts.toDto(
    userId: UUID,
    clock: Clock,
): AdminPartnerLinkReviewDto {
    val current = links.firstOrNull { it.status == "active" && it.unlinkedAt == null }
    val recentUnlinkCutoff = clock.instant().minus(Duration.ofDays(30))
    return AdminPartnerLinkReviewDto(
        userId = userId.toString(),
        partnerVisibility = partnerVisibility,
        currentLink = current?.toDto(),
        history = links.filterNot { it.linkId == current?.linkId }.map { it.toDto() },
        activeLinkCount = links.count { it.status == "active" && it.unlinkedAt == null },
        pastLinkCount = links.count { it.status != "active" || it.unlinkedAt != null },
        recentUnlinkCount =
            links.count { link ->
                link.unlinkedAt?.let { it >= recentUnlinkCutoff } == true
            },
        reportsAgainstUserCount = reportsAgainstUserCount,
        reportsAgainstCurrentPartnerCount = reportsAgainstCurrentPartnerCount,
    )
}

private fun AdminPartnerLinkFact.toDto(): AdminPartnerLinkDto =
    AdminPartnerLinkDto(
        linkId = linkId.toString(),
        otherUserId = otherUserId.toString(),
        status = status,
        direction = direction,
        createdAt = createdAt.toString(),
        acceptedAt = acceptedAt?.toString(),
        unlinkedAt = unlinkedAt?.toString(),
    )
