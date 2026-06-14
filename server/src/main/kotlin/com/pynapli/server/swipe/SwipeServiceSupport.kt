package com.pynapli.server.swipe

import com.pynapli.server.presence.PresenceService
import java.util.UUID

internal suspend fun onlineSwipeUserIdsFor(
    presenceService: PresenceService?,
    userIds: Collection<UUID>,
): Set<UUID> {
    val service = presenceService ?: return emptySet()
    if (userIds.isEmpty()) return emptySet()
    return runCatching { service.onlineUserIds(userIds) }.getOrDefault(emptySet())
}

internal fun IssuedSwipeDeckSession.toSwipeDeckSessionRecord(
    viewerUserId: UUID,
    scopeHash: String,
    radiusMeters: Int,
    pageLimit: Int,
    filterPayload: String,
): SwipeDeckSessionRecord =
    SwipeDeckSessionRecord(
        sessionId = sessionId,
        deckSessionToken = deckSessionId,
        deckSessionTokenHash = deckSessionTokenHash,
        viewerUserId = viewerUserId,
        scopeHash = scopeHash,
        radiusMeters = radiusMeters,
        pageLimit = pageLimit,
        filterPayload = filterPayload,
        generatedAt = generatedAt,
        expiresAt = expiresAt,
    )

internal fun SwipeDeckSessionRecord.toIssuedSwipeDeckSession(): IssuedSwipeDeckSession =
    IssuedSwipeDeckSession(
        sessionId = sessionId,
        deckSessionId = deckSessionToken,
        deckSessionTokenHash = deckSessionTokenHash,
        generatedAt = generatedAt,
        expiresAt = expiresAt,
    )
