package com.pynapli.server.activity

import com.pynapli.server.media.MediaException
import com.pynapli.server.media.MediaService
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import java.util.UUID

internal class ActivitySignalActions(
    private val repository: ActivityRepository,
    private val mediaService: MediaService?,
    private val blockRepository: BlockRepository?,
    private val notificationPublisher: NotificationPublisher?,
    private val rateLimitService: RateLimitService?,
    private val riskPolicyService: RiskPolicyService?,
    private val activityBroadcaster: ActivityBroadcaster?,
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    /**
     * The realtime refresh hint for [recipientUserId], unless [actorUserId] hides from them ("Hide from
     * people you know"). The row is written either way (the actor's own view never changes); the
     * recipient's reads leave it out, and so does this hint.
     */
    private suspend fun publishDelta(
        recipientUserId: UUID,
        actorUserId: UUID,
        eventType: String,
    ) {
        if (contactBlockVisibility?.isHiddenFrom(viewerUserId = recipientUserId, otherUserId = actorUserId) == true) return
        activityBroadcaster.publishActivityDelta(recipientUserId, eventType)
    }

    /** True when [targetUserId] hides from [actorUserId]: the actor can't reach them by a stale id. */
    private suspend fun targetHidesFrom(
        actorUserId: UUID,
        targetUserId: UUID,
    ): Boolean = contactBlockVisibility?.isHiddenFrom(viewerUserId = actorUserId, otherUserId = targetUserId) == true

    suspend fun recordProfileView(
        viewerUserId: UUID,
        targetUserId: UUID,
    ) {
        if (viewerUserId == targetUserId) return
        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) return
        // Backstop (the profile fetch already answers PROFILE_NOT_FOUND): no view of someone hiding from the viewer.
        if (targetHidesFrom(viewerUserId, targetUserId)) return
        if (riskPolicyService?.shouldSuppressPassiveActivity(viewerUserId) == true) return
        if (rateLimitService?.check(viewerUserId, RateLimitedAction.PROFILE_VIEW)?.allowed == false) return
        if (!repository.recordProfileView(viewerUserId, targetUserId)) return
        publishDelta(recipientUserId = targetUserId, actorUserId = viewerUserId, eventType = "profile_view")
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
        if (targetHidesFrom(senderUserId, targetUserId)) throw ActivityException("FLIRT_NOT_ALLOWED")
        if (rateLimitService?.check(senderUserId, RateLimitedAction.FLIRT)?.allowed == false) {
            throw ActivityException("RATE_LIMITED")
        }
        // Only the deliberate Playing Tonight origin is recognised; anything else is dropped to
        // null so an arbitrary client-supplied label can never leak into the recipient's feed.
        val normalizedSource = source?.takeIf { it == ACTIVITY_SOURCE_PLAYING_TONIGHT_VALUE }
        if (!repository.recordFlirt(senderUserId, targetUserId, normalizedSource)) {
            throw ActivityException("FLIRT_NOT_ALLOWED")
        }
        contactBlockVisibility?.noteActToward(senderUserId, targetUserId)
        publishDelta(recipientUserId = targetUserId, actorUserId = senderUserId, eventType = "flirt")
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
        if (targetHidesFrom(requesterUserId, targetUserId)) throw ActivityException("ALBUM_REQUEST_NOT_ALLOWED")
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
        contactBlockVisibility?.noteActToward(requesterUserId, targetUserId)
        publishDelta(recipientUserId = targetUserId, actorUserId = requesterUserId, eventType = "album_request")
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
        metadataJson: String = "{}",
    ) {
        if (actorUserId == targetUserId) return
        if (eventType !in ACTIVITY_SWIPE_INTEREST_EVENT_TYPES) throw ActivityException("INVALID_ACTIVITY_EVENT_TYPE")
        if (blockRepository?.isBlockedEitherWay(actorUserId, targetUserId) == true) return
        if (riskPolicyService?.isSocialActionRestricted(actorUserId) == true) return
        if (repository.recordSwipeInterest(actorUserId, targetUserId, eventType, metadataJson)) {
            publishDelta(recipientUserId = targetUserId, actorUserId = actorUserId, eventType = eventType)
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
            publishDelta(recipientUserId = userA, actorUserId = userB, eventType = "match")
        }
        if (repository.recordMatch(recipientUserId = userB, actorUserId = userA, matchId = matchId)) {
            publishDelta(recipientUserId = userB, actorUserId = userA, eventType = "match")
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
            publishDelta(recipientUserId = inviteeUserId, actorUserId = inviterUserId, eventType = "partner_chat_invite")
        }
    }

    /**
     * ACTIVITY_SPEC "Private Album Grant": the owner approved a request, so tell the requester they
     * can now see the album. A denial stays silent on purpose: nobody is told they were turned down.
     */
    suspend fun recordAlbumGranted(
        ownerUserId: UUID,
        requesterUserId: UUID,
    ) {
        if (ownerUserId == requesterUserId) return
        if (blockRepository?.isBlockedEitherWay(ownerUserId, requesterUserId) == true) return
        if (!repository.recordAlbumGranted(ownerUserId, requesterUserId)) return
        publishDelta(recipientUserId = requesterUserId, actorUserId = ownerUserId, eventType = "album_granted")
        notificationPublisher?.enqueue(
            NotificationEventDraft(
                recipientUserId = requesterUserId,
                actorUserId = ownerUserId,
                kind = NotificationKind.ALBUM_GRANTED,
                subjectId = ownerUserId,
                idempotencyKey = "album_granted:$ownerUserId:$requesterUserId:${System.currentTimeMillis() / ALBUM_GRANT_PUSH_WINDOW_MILLIS}",
                payload =
                    activityNotificationPayload(
                        eventType = "album_granted",
                        extra = mapOf("ownerUserId" to ownerUserId.toString()),
                    ),
            ),
        )
    }

    suspend fun recordPsyncSessionEvent(
        recipientUserId: UUID,
        actorUserId: UUID,
        eventType: String,
        sessionId: UUID,
    ) {
        if (recipientUserId == actorUserId) return
        if (blockRepository?.isBlockedEitherWay(recipientUserId, actorUserId) == true) return
        if (repository.recordPsyncSessionEvent(recipientUserId, actorUserId, eventType, sessionId)) {
            publishDelta(recipientUserId = recipientUserId, actorUserId = actorUserId, eventType = eventType)
        }
    }
}

// One album-shared push per owner -> requester per day, however often access is toggled.
private const val ALBUM_GRANT_PUSH_WINDOW_MILLIS = 24L * 60 * 60 * 1000
