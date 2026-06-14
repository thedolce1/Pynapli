package com.pynapli.server.activity

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import java.util.UUID

internal class ActivityFeedActions(
    private val repository: ActivityRepository,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    private val blockRepository: BlockRepository?,
    private val riskPolicyService: RiskPolicyService?,
    private val subscriptionService: SubscriptionService?,
    private val cursorCodec: ActivityCursorCodec,
) {
    suspend fun listActivity(
        recipientUserId: UUID,
        limit: Int = ACTIVITY_DEFAULT_LIMIT,
        tabText: String? = null,
        cursorText: String? = null,
    ): ActivityFeedResponseDto {
        val normalizedLimit = limit.coerceIn(1, ACTIVITY_MAX_LIMIT)
        val tab = ActivityFeedTab.from(tabText)
        val cursorScopeHash = ActivityCursorCodec.scopeHash(recipientUserId, tab)
        val cursor =
            cursorText
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { token ->
                    try {
                        cursorCodec.decode(token, cursorScopeHash)
                    } catch (_: ActivityCursorException) {
                        throw ActivityException("INVALID_ACTIVITY_CURSOR")
                    }
                }
        val policy = subscriptionService?.getPolicy(recipientUserId) ?: EntitlementPolicy.unrestricted()
        val remainingReveal =
            if (policy.fullActivityReveal) {
                Int.MAX_VALUE
            } else {
                (policy.activityRevealLimit - (cursor?.seenCount ?: 0)).coerceAtLeast(0)
            }
        if (remainingReveal == 0) {
            return ActivityFeedResponseDto(
                items = emptyList(),
                nextCursor = null,
                adSupported = policy.adSupported,
                entitlementTier = policy.tier,
                revealLimit = policy.activityRevealLimit,
                gatedCount = 0,
            )
        }

        val pageLimit = normalizedLimit.coerceAtMost(remainingReveal)
        val events =
            repository.listActivity(
                ActivityFeedQuery(
                    recipientUserId = recipientUserId,
                    eventTypes = tab.eventTypes,
                    limit = pageLimit + 1,
                    cursor = cursor,
                ),
            )
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(recipientUserId).orEmpty()
        val blockVisible =
            if (blockedRelatedIds.isEmpty()) {
                events
            } else {
                events.filterNot { it.actorUserId in blockedRelatedIds }
            }
        val visible =
            if (riskPolicyService == null) {
                blockVisible
            } else {
                buildList {
                    for (event in blockVisible) {
                        if (!riskPolicyService.shouldSuppressPassiveActivity(event.actorUserId)) {
                            add(event)
                        }
                    }
                }
            }
        val revealed = visible.take(pageLimit)
        val seenCount = (cursor?.seenCount ?: 0) + revealed.size
        val hasMoreVisibleInFetchedPage = visible.size > pageLimit
        val canPageWithinReveal = policy.fullActivityReveal || seenCount < policy.activityRevealLimit
        val nextCursor =
            if (hasMoreVisibleInFetchedPage && canPageWithinReveal && revealed.isNotEmpty()) {
                cursorCodec.encode(cursorScopeHash, revealed.last().toCursorAnchor(seenCount))
            } else {
                null
            }
        val gatedCount =
            if (!policy.fullActivityReveal && nextCursor == null) {
                (visible.size - revealed.size).coerceAtLeast(0)
            } else {
                0
            }
        return ActivityFeedResponseDto(
            items = revealed.map { it.toDto(mediaAssetUrlResolver) },
            nextCursor = nextCursor,
            adSupported = policy.adSupported,
            entitlementTier = policy.tier,
            revealLimit = if (policy.fullActivityReveal) null else policy.activityRevealLimit,
            gatedCount = gatedCount,
        )
    }
}
