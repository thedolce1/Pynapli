package com.pynapli.server.activity

import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaException
import com.pynapli.server.media.MediaService
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import java.util.UUID

class ActivityService(
    private val repository: ActivityRepository,
    private val mediaService: MediaService? = null,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null,
    private val notificationPublisher: NotificationPublisher? = null,
    private val rateLimitService: RateLimitService? = null,
    private val riskPolicyService: RiskPolicyService? = null,
    private val subscriptionService: SubscriptionService? = null,
    private val activityBroadcaster: ActivityBroadcaster? = null,
    private val cursorCodec: ActivityCursorCodec = ActivityCursorCodec("pynapli-local-dev-activity-cursor-secret"),
) {
    suspend fun recordProfileView(
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        if (viewerUserId == targetUserId) return
        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) return
        if (riskPolicyService?.shouldSuppressPassiveActivity(viewerUserId) == true) return
        if (rateLimitService?.check(viewerUserId, RateLimitedAction.PROFILE_VIEW)?.allowed == false) return
        if (!repository.recordProfileView(viewerUserId, targetUserId)) return
        publishActivityDelta(targetUserId, "profile_view")
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = targetUserId,
                actorUserId = viewerUserId,
                kind = NotificationKind.PROFILE_VIEW,
                subjectId = targetUserId,
                idempotencyKey = "profile_view:$targetUserId:$viewerUserId",
                payload =
                    activityNotificationPayload(
                        eventType = "profile_view",
                        extra = mapOf("viewerUserId" to viewerUserId.toString()),
                    ),
            ),
        )
    }

    suspend fun sendFlirt(
        senderUserId: UUID,
        targetUserId: UUID,
        source: String? = null,
    ): SendFlirtResponseDto {
        if (senderUserId == targetUserId) throw ActivityException("INVALID_FLIRT_TARGET")
        if (riskPolicyService?.isSocialActionRestricted(senderUserId) == true) {
            throw ActivityException("RISK_RESTRICTED")
        }
        if (blockRepository?.isBlockedEitherWay(senderUserId, targetUserId) == true) {
            throw ActivityException("FLIRT_NOT_ALLOWED")
        }
        if (rateLimitService?.check(senderUserId, RateLimitedAction.FLIRT)?.allowed == false) {
            throw ActivityException("RATE_LIMITED")
        }
        // Only the deliberate Playing Tonight origin is recognised; anything else is dropped to
        // null so an arbitrary client-supplied label can never leak into the recipient's feed.
        val normalizedSource = source?.takeIf { it == ACTIVITY_SOURCE_PLAYING_TONIGHT }
        if (!repository.recordFlirt(senderUserId, targetUserId, normalizedSource)) {
            throw ActivityException("FLIRT_NOT_ALLOWED")
        }
        publishActivityDelta(targetUserId, "flirt")
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = targetUserId,
                actorUserId = senderUserId,
                kind = NotificationKind.FLIRT,
                subjectId = senderUserId,
                idempotencyKey = "flirt:$senderUserId:$targetUserId",
                payload =
                    activityNotificationPayload(
                        eventType = "flirt",
                        extra = mapOf("actorUserId" to senderUserId.toString()),
                    ),
            ),
        )
        return SendFlirtResponseDto(sent = true)
    }

    suspend fun requestAlbumAccess(
        requesterUserId: UUID,
        targetUserId: UUID,
    ): RequestAlbumAccessResponseDto {
        if (requesterUserId == targetUserId) throw ActivityException("INVALID_ALBUM_REQUEST_TARGET")
        if (riskPolicyService?.isSocialActionRestricted(requesterUserId) == true) {
            throw ActivityException("RISK_RESTRICTED")
        }
        if (blockRepository?.isBlockedEitherWay(requesterUserId, targetUserId) == true) {
            throw ActivityException("ALBUM_REQUEST_NOT_ALLOWED")
        }
        if (rateLimitService?.check(requesterUserId, RateLimitedAction.ALBUM_REQUEST)?.allowed == false) {
            throw ActivityException("RATE_LIMITED")
        }
        if (!repository.recordAlbumRequest(requesterUserId, targetUserId)) {
            throw ActivityException("ALBUM_REQUEST_NOT_ALLOWED")
        }
        val mediaRequested =
            runCatching {
                mediaService?.requestPrivateAlbumAccess(
                    requesterUserId = requesterUserId,
                    ownerUserId = targetUserId,
                ) ?: true
            }.getOrElse { error ->
                if (error is MediaException) throw ActivityException(error.code)
                throw error
            }
        if (!mediaRequested) throw ActivityException("ALBUM_REQUEST_NOT_ALLOWED")
        publishActivityDelta(targetUserId, "album_request")
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = targetUserId,
                actorUserId = requesterUserId,
                kind = NotificationKind.ALBUM_REQUEST,
                subjectId = requesterUserId,
                idempotencyKey = "album_request:$requesterUserId:$targetUserId",
                payload =
                    activityNotificationPayload(
                        eventType = "album_request",
                        extra = mapOf("requesterUserId" to requesterUserId.toString()),
                    ),
            ),
        )
        return RequestAlbumAccessResponseDto(requested = true)
    }

    suspend fun recordSwipeInterest(
        actorUserId: UUID,
        targetUserId: UUID,
        eventType: String,
    ) {
        if (actorUserId == targetUserId) return
        if (eventType !in SWIPE_INTEREST_EVENT_TYPES) throw ActivityException("INVALID_ACTIVITY_EVENT_TYPE")
        if (blockRepository?.isBlockedEitherWay(actorUserId, targetUserId) == true) return
        if (riskPolicyService?.isSocialActionRestricted(actorUserId) == true) return
        if (repository.recordSwipeInterest(actorUserId, targetUserId, eventType)) {
            publishActivityDelta(targetUserId, eventType)
        }
    }

    suspend fun recordMatch(
        userA: UUID,
        userB: UUID,
        matchId: UUID,
    ) {
        if (userA == userB) return
        if (blockRepository?.isBlockedEitherWay(userA, userB) == true) return
        if (repository.recordMatch(recipientUserId = userA, actorUserId = userB, matchId = matchId)) {
            publishActivityDelta(userA, "match")
        }
        if (repository.recordMatch(recipientUserId = userB, actorUserId = userA, matchId = matchId)) {
            publishActivityDelta(userB, "match")
        }
    }

    suspend fun recordPartnerChatInvite(
        inviterUserId: UUID,
        inviteeUserId: UUID,
        conversationId: UUID,
    ) {
        if (inviterUserId == inviteeUserId) return
        if (blockRepository?.isBlockedEitherWay(inviterUserId, inviteeUserId) == true) return
        if (riskPolicyService?.isSocialActionRestricted(inviterUserId) == true) return
        if (repository.recordPartnerChatInvite(inviterUserId, inviteeUserId, conversationId)) {
            publishActivityDelta(inviteeUserId, "partner_chat_invite")
        }
    }

    suspend fun respondToAlbumAccessRequest(
        ownerUserId: UUID,
        eventId: UUID,
        approve: Boolean,
    ): RespondAlbumAccessResponseDto {
        val event =
            repository.getAlbumRequestEvent(ownerUserId, eventId)
                ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
        val status =
            mediaService?.decidePrivateAlbumAccessRequest(
                ownerUserId = event.ownerUserId,
                requesterUserId = event.requesterUserId,
                approved = approve,
            ) ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
        return RespondAlbumAccessResponseDto(
            eventId = event.eventId.toString(),
            status = status,
        )
    }

    suspend fun dismissActivityEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): DismissActivityEventResponseDto {
        if (!repository.dismissActivityEvent(recipientUserId, eventId)) {
            throw ActivityException("ACTIVITY_EVENT_NOT_FOUND")
        }
        return DismissActivityEventResponseDto(eventId = eventId.toString(), dismissed = true)
    }

    suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryDto {
        val summary = repository.getActivitySummary(recipientUserId)
        return ActivitySummaryDto(
            badgeCount = summary.badgeCount,
            profileViewCount = summary.profileViewCount,
            flirtCount = summary.flirtCount,
            likeCount = summary.likeCount,
            matchCount = summary.matchCount,
            albumRequestCount = summary.albumRequestCount,
            partnerInviteCount = summary.partnerInviteCount,
            latestBadgeOccurredAt = summary.latestBadgeOccurredAt?.toString(),
        )
    }

    suspend fun markActivityEventsSeen(
        recipientUserId: UUID,
        eventIdTexts: List<String>,
    ): MarkActivitySeenResponseDto {
        if (eventIdTexts.size > MAX_MARK_SEEN_IDS) {
            throw ActivityException("TOO_MANY_ACTIVITY_EVENTS")
        }
        val eventIds =
            eventIdTexts
                .map { value ->
                    runCatching { UUID.fromString(value) }
                        .getOrElse { throw ActivityException("INVALID_ACTIVITY_EVENT") }
                }.toSet()
        if (eventIds.isEmpty()) return MarkActivitySeenResponseDto(markedCount = 0)

        return MarkActivitySeenResponseDto(
            markedCount = repository.markActivityEventsSeen(recipientUserId, eventIds),
        )
    }

    suspend fun listActivity(
        recipientUserId: UUID,
        limit: Int = DEFAULT_LIMIT,
        tabText: String? = null,
        cursorText: String? = null,
    ): ActivityFeedResponseDto {
        val normalizedLimit = limit.coerceIn(1, MAX_LIMIT)
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
            items = revealed.map { it.toDto() },
            nextCursor = nextCursor,
            adSupported = policy.adSupported,
            entitlementTier = policy.tier,
            revealLimit = if (policy.fullActivityReveal) null else policy.activityRevealLimit,
            gatedCount = gatedCount,
        )
    }

    private fun ActivityEventRecord.toDto(): ActivityEventDto =
        ActivityEventDto(
            eventId = eventId.toString(),
            type = type,
            actorUserId = actorUserId.toString(),
            actorDisplayName = actorDisplayName,
            actorPhotoUrl = actorProfilePhotoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
            count = count,
            occurredAt = occurredAt.toString(),
            seen = seen,
            distanceLabel = if (showDistance) distanceMeters?.toPublicDistanceLabel() else null,
            distanceMeters = if (showDistance) distanceMeters?.toPublicDistanceMeters() else null,
            albumRequestStatus = albumRequestStatus,
            matchId = matchId,
            conversationId = conversationId,
            source = source,
        )

    private suspend fun publishActivityDelta(
        recipientUserId: UUID,
        eventType: String,
    ) {
        activityBroadcaster?.emit(
            ActivityRealtimeDelta(
                recipientUserId = recipientUserId,
                type = eventType,
            ),
        )
    }

    private fun activityNotificationPayload(
        eventType: String,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> =
        extra +
            mapOf(
                "target" to "activity",
                "activityTab" to ActivityFeedTab.fromEventType(eventType).value,
                "activityEventType" to eventType,
            )

    companion object {
        const val ACTIVITY_SOURCE_PLAYING_TONIGHT = "playing_tonight"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100
        private const val MAX_MARK_SEEN_IDS = 100
        private val SWIPE_INTEREST_EVENT_TYPES = setOf("swipe_like", "pineapple_like", "intro_note")
    }
}

class ActivityException(
    val code: String,
) : RuntimeException(code)
