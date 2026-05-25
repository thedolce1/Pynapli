package com.pynapli.server.swipe

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.profiling.profile
import com.pynapli.shared.network.dto.SwipeAction
import java.util.UUID

internal class SwipeActionSideEffects(
    private val activityService: ActivityService?,
    private val notificationPublisher: NotificationPublisher?
) {
    suspend fun recordActivityForAction(
        viewerUserId: UUID,
        targetUserId: UUID,
        action: String,
        match: MatchRecord?
    ) {
        val activity = activityService ?: return
        profile("swipe.action.activity") {
            when (action) {
                SwipeAction.LIKE -> activity.recordSwipeInterest(viewerUserId, targetUserId, "swipe_like")
                SwipeAction.PINEAPPLE_LIKE -> activity.recordSwipeInterest(
                    viewerUserId,
                    targetUserId,
                    "pineapple_like"
                )

                SwipeAction.INTRO_NOTE -> activity.recordSwipeInterest(viewerUserId, targetUserId, "intro_note")
                else -> Unit
            }
            match?.let { activity.recordMatch(it.userA, it.userB, it.matchId) }
        }
    }

    suspend fun notifyMatch(
        viewerUserId: UUID,
        targetUserId: UUID,
        match: MatchRecord
    ) {
        profile("swipe.action.notify") {
            notificationPublisher?.enqueue(
                NotificationEventDraft(
                    recipientUserId = targetUserId,
                    actorUserId = viewerUserId,
                    kind = NotificationKind.MATCH,
                    subjectId = match.matchId,
                    idempotencyKey = "match:${match.matchId}:$targetUserId",
                    payload = mapOf(
                        "target" to "activity",
                        "activityTab" to "matches",
                        "activityEventType" to "match",
                        "matchId" to match.matchId.toString(),
                        "matchedUserId" to viewerUserId.toString()
                    )
                )
            )
        }
    }
}
