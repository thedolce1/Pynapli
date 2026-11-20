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
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
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
        val hiddenFromRecipient = contactBlockVisibility?.hiddenFrom(recipientUserId).orEmpty()
        val blockVisible =
            if (blockedRelatedIds.isEmpty() && hiddenFromRecipient.isEmpty()) {
                events
            } else {
                // Backstop: the SQL already leaves out anyone hiding from the recipient.
                events.filterNot { it.actorUserId in blockedRelatedIds || it.actorUserId in hiddenFromRecipient }
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
        // The pair name on a "liked you together" row never names someone hiding from the recipient.
        return ActivityFeedResponseDto(
            items = revealed.foldPairLikes(blockedRelatedIds + hiddenFromRecipient).map { it.toDto(mediaAssetUrlResolver) },
            nextCursor = nextCursor,
            entitlementTier = policy.tier,
            revealLimit = if (policy.fullActivityReveal) null else policy.activityRevealLimit,
            gatedCount = gatedCount,
        )
    }
}

/**
 * A Psync couple who both liked the recipient produce two like events (each is a real like the
 * recipient can answer). Shown once: "Maya & Alex liked you together" on the first, the second
 * dropped. If the partner is blocked, gone, or their own like isn't on this page, the row stays an
 * ordinary single like.
 */
internal fun List<ActivityEventRecord>.foldPairLikes(blockedRelatedIds: Set<java.util.UUID>): List<ActivityEventRecord> {
    val shown = mutableSetOf<Pair<String, java.util.UUID>>()
    return mapNotNull { event ->
        val partner = event.pairUserId?.takeIf { it !in blockedRelatedIds && event.pairDisplayName != null }
        val session = event.pairSessionId
        if (partner == null || session == null) return@mapNotNull event.copy(pairUserId = null, pairDisplayName = null)
        val partnerAlsoListed = any { it.actorUserId == partner && it.pairSessionId == session }
        when {
            !partnerAlsoListed -> event.copy(pairUserId = null, pairDisplayName = null)
            (session to partner) in shown -> null
            else -> {
                shown += session to event.actorUserId
                event
            }
        }
    }
}
