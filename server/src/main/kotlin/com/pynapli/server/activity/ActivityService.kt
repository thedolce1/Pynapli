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
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
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
    private val cursorCodec: ActivityCursorCodec = ActivityCursorCodec("pynapli-local-dev-activity-cursor-secret")
) {
    suspend fun recordProfileView(viewerUserId: UUID, targetUserId: UUID) {
        if (viewerUserId == targetUserId) return
        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) return
        if (riskPolicyService?.shouldSuppressPassiveActivity(viewerUserId) == true) return
        if (rateLimitService?.check(viewerUserId, RateLimitedAction.PROFILE_VIEW)?.allowed == false) return
        if (!repository.recordProfileView(viewerUserId, targetUserId)) return
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = targetUserId,
                actorUserId = viewerUserId,
                kind = NotificationKind.PROFILE_VIEW,
                subjectId = targetUserId,
                idempotencyKey = "profile_view:$targetUserId:$viewerUserId",
                payload = mapOf("viewerUserId" to viewerUserId.toString())
            )
        )
    }

    suspend fun sendFlirt(senderUserId: UUID, targetUserId: UUID): SendFlirtResponseDto {
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
        if (!repository.recordFlirt(senderUserId, targetUserId)) {
            throw ActivityException("FLIRT_NOT_ALLOWED")
        }
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = targetUserId,
                actorUserId = senderUserId,
                kind = NotificationKind.FLIRT,
                subjectId = senderUserId,
                idempotencyKey = "flirt:$senderUserId:$targetUserId",
                payload = mapOf("actorUserId" to senderUserId.toString())
            )
        )
        return SendFlirtResponseDto(sent = true)
    }

    suspend fun requestAlbumAccess(requesterUserId: UUID, targetUserId: UUID): RequestAlbumAccessResponseDto {
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
        val mediaRequested = runCatching {
            mediaService?.requestPrivateAlbumAccess(
                requesterUserId = requesterUserId,
                ownerUserId = targetUserId
            ) ?: true
        }.getOrElse { error ->
            if (error is MediaException) throw ActivityException(error.code)
            throw error
        }
        if (!mediaRequested) throw ActivityException("ALBUM_REQUEST_NOT_ALLOWED")
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = targetUserId,
                actorUserId = requesterUserId,
                kind = NotificationKind.ALBUM_REQUEST,
                subjectId = requesterUserId,
                idempotencyKey = "album_request:$requesterUserId:$targetUserId",
                payload = mapOf("requesterUserId" to requesterUserId.toString())
            )
        )
        return RequestAlbumAccessResponseDto(requested = true)
    }

    suspend fun recordSwipeInterest(actorUserId: UUID, targetUserId: UUID, eventType: String) {
        if (actorUserId == targetUserId) return
        if (eventType !in SWIPE_INTEREST_EVENT_TYPES) throw ActivityException("INVALID_ACTIVITY_EVENT_TYPE")
        if (blockRepository?.isBlockedEitherWay(actorUserId, targetUserId) == true) return
        if (riskPolicyService?.isSocialActionRestricted(actorUserId) == true) return
        repository.recordSwipeInterest(actorUserId, targetUserId, eventType)
    }

    suspend fun recordMatch(userA: UUID, userB: UUID, matchId: UUID) {
        if (userA == userB) return
        if (blockRepository?.isBlockedEitherWay(userA, userB) == true) return
        repository.recordMatch(recipientUserId = userA, actorUserId = userB, matchId = matchId)
        repository.recordMatch(recipientUserId = userB, actorUserId = userA, matchId = matchId)
    }

    suspend fun recordPartnerChatInvite(inviterUserId: UUID, inviteeUserId: UUID, conversationId: UUID) {
        if (inviterUserId == inviteeUserId) return
        if (blockRepository?.isBlockedEitherWay(inviterUserId, inviteeUserId) == true) return
        if (riskPolicyService?.isSocialActionRestricted(inviterUserId) == true) return
        repository.recordPartnerChatInvite(inviterUserId, inviteeUserId, conversationId)
    }

    suspend fun respondToAlbumAccessRequest(
        ownerUserId: UUID,
        eventId: UUID,
        approve: Boolean
    ): RespondAlbumAccessResponseDto {
        val event = repository.getAlbumRequestEvent(ownerUserId, eventId)
            ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
        val status = mediaService?.decidePrivateAlbumAccessRequest(
            ownerUserId = event.ownerUserId,
            requesterUserId = event.requesterUserId,
            approved = approve
        ) ?: throw ActivityException("ALBUM_REQUEST_NOT_FOUND")
        return RespondAlbumAccessResponseDto(
            eventId = event.eventId.toString(),
            status = status
        )
    }

    suspend fun dismissActivityEvent(recipientUserId: UUID, eventId: UUID): DismissActivityEventResponseDto {
        if (!repository.dismissActivityEvent(recipientUserId, eventId)) {
            throw ActivityException("ACTIVITY_EVENT_NOT_FOUND")
        }
        return DismissActivityEventResponseDto(eventId = eventId.toString(), dismissed = true)
    }

    suspend fun listActivity(
        recipientUserId: UUID,
        limit: Int = DEFAULT_LIMIT,
        tabText: String? = null,
        cursorText: String? = null
    ): ActivityFeedResponseDto {
        val normalizedLimit = limit.coerceIn(1, MAX_LIMIT)
        val tab = ActivityFeedTab.from(tabText)
        val cursorScopeHash = ActivityCursorCodec.scopeHash(recipientUserId, tab)
        val cursor = cursorText
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
        val remainingReveal = if (policy.fullActivityReveal) {
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
                gatedCount = 0
            )
        }

        val pageLimit = normalizedLimit.coerceAtMost(remainingReveal)
        val events = repository.listActivity(
            ActivityFeedQuery(
                recipientUserId = recipientUserId,
                eventTypes = tab.eventTypes,
                limit = pageLimit + 1,
                cursor = cursor
            )
        )
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(recipientUserId).orEmpty()
        val blockVisible = if (blockedRelatedIds.isEmpty()) {
            events
        } else {
            events.filterNot { it.actorUserId in blockedRelatedIds }
        }
        val visible = if (riskPolicyService == null) {
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
        val nextCursor = if (hasMoreVisibleInFetchedPage && canPageWithinReveal && revealed.isNotEmpty()) {
            cursorCodec.encode(cursorScopeHash, revealed.last().toCursorAnchor(seenCount))
        } else {
            null
        }
        val gatedCount = if (!policy.fullActivityReveal && nextCursor == null) {
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
            gatedCount = gatedCount
        )
    }

    private fun ActivityEventRecord.toDto(): ActivityEventDto = ActivityEventDto(
        eventId = eventId.toString(),
        type = type,
        actorUserId = actorUserId.toString(),
        actorDisplayName = actorDisplayName,
        actorPhotoUrl = actorProfilePhotoMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
        count = count,
        occurredAt = occurredAt.toString(),
        distanceLabel = if (showDistance) distanceMeters?.toPublicDistanceLabel() else null,
        distanceMeters = if (showDistance) distanceMeters?.toPublicDistanceMeters() else null,
        albumRequestStatus = albumRequestStatus,
        matchId = matchId,
        conversationId = conversationId
    )

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100
        private val SWIPE_INTEREST_EVENT_TYPES = setOf("swipe_like", "pineapple_like", "intro_note")
    }
}

class ActivityException(val code: String) : RuntimeException(code)
