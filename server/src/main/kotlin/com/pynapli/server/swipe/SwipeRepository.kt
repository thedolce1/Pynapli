package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import java.time.Instant
import java.util.UUID

interface SwipeRepository {
    suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters = DiscoveryFilters(),
        excludedTargetUserIds: Set<UUID> = emptySet()
    ): SwipeDeckResult

    suspend fun upsertDeckSession(session: SwipeDeckSessionRecord, cards: List<SwipeDeckSessionCardRecord>)

    suspend fun findDeckSession(sessionId: UUID, viewerUserId: UUID): SwipeDeckSessionRecord?

    suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID>

    suspend fun isDeckCardActionable(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant
    ): Boolean

    suspend fun markDeckCardActionRecorded(
        sessionId: UUID,
        cardTokenHash: String,
        targetUserId: UUID,
        action: String,
        now: Instant
    )

    suspend fun isActionTargetEligible(
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean = false
    ): Boolean

    /**
     * One-shot precondition gate for action submit. Combines the deck-card actionability
     * check and the target eligibility check that submitAction used to call separately.
     * The default impl delegates to the existing two methods so test fakes do not have
     * to change; JdbcSwipeRepository overrides with a single SQL round-trip.
     */
    suspend fun checkActionPreconditions(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean,
        now: Instant
    ): SwipeActionPreconditionCheck = SwipeActionPreconditionCheck(
        deckCardActionable = isDeckCardActionable(
            sessionId = sessionId,
            cardTokenHash = cardTokenHash,
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
            now = now
        ),
        targetEligible = isActionTargetEligible(
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
            radiusMeters = radiusMeters,
            requireIntroNotesAllowed = requireIntroNotesAllowed
        )
    )

    suspend fun findSwipeActionById(actionId: UUID, actorUserId: UUID): SwipeActionRecord?

    suspend fun findLatestRewindableAction(
        viewerUserId: UUID,
        radiusMeters: Int
    ): SwipeRewindCandidateRecord?

    /**
     * Inserts the swipe action if the actor has not already decided on this target.
     * If the action is `like` and the target had already liked the actor, also inserts a match
     * row in canonical order and returns it.
     */
    suspend fun recordSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        noteText: String? = null,
        matchIdFactory: () -> UUID = UUID::randomUUID
    ): SwipeActionResult

    suspend fun rewindSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        session: SwipeDeckSessionRecord,
        card: SwipeDeckSessionCardRecord,
        rewoundAt: Instant
    ): Boolean

    suspend fun listMatches(viewerUserId: UUID, limit: Int): List<MatchSummaryRecord>
}

data class SwipeDeckResult(
    val viewerHadLocation: Boolean,
    val cards: List<SwipeCardRecord>
)

data class SwipeActionPreconditionCheck(
    val deckCardActionable: Boolean,
    val targetEligible: Boolean
)

data class SwipeDeckSessionRecord(
    val sessionId: UUID,
    val deckSessionToken: String,
    val deckSessionTokenHash: String,
    val viewerUserId: UUID,
    val scopeHash: String,
    val radiusMeters: Int,
    val pageLimit: Int,
    val filterPayload: String,
    val generatedAt: Instant,
    val expiresAt: Instant
)

data class SwipeDeckSessionCardRecord(
    val cardId: UUID,
    val sessionId: UUID,
    val viewerUserId: UUID,
    val targetUserId: UUID,
    val cardTokenHash: String,
    val position: Int,
    val dealtAt: Instant
)

data class SwipeCardRecord(
    val userId: UUID,
    val displayName: String,
    val displayAge: Int?,
    val thumbnailMediaAssetId: UUID?,
    val showDistance: Boolean,
    val distanceMeters: Double,
    val linkedPartnerUserId: UUID? = null,
    val linkedPartnerDisplayName: String? = null,
    val linkedPartnerThumbnailMediaAssetId: UUID? = null,
    val bio: String? = null,
    val tags: List<String> = emptyList(),
    val thumbnailMediaAssetIds: List<UUID> = emptyList(),
    val verified: Boolean = false
)

data class SwipeActionResult(
    val recorded: Boolean,
    val match: MatchRecord?
)

data class SwipeActionRecord(
    val actionId: UUID,
    val actorUserId: UUID,
    val targetUserId: UUID,
    val action: String,
    val noteText: String?,
    val createdAt: Instant,
    val rewoundAt: Instant?,
    val match: MatchRecord?
)

data class SwipeRewindCandidateRecord(
    val actionId: UUID,
    val targetUserId: UUID,
    val action: String,
    val card: SwipeCardRecord
)

data class MatchRecord(
    val matchId: UUID,
    val userA: UUID,
    val userB: UUID,
    val createdAt: Instant
)

data class MatchSummaryRecord(
    val matchId: UUID,
    val matchedUserId: UUID,
    val matchedDisplayName: String?,
    val matchedThumbnailMediaAssetId: UUID?,
    val createdAt: Instant
)
