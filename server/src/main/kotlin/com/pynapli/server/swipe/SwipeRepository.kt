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
        excludedTargetUserIds: Set<UUID> = emptySet(),
    ): SwipeDeckResult

    suspend fun upsertDeckSession(
        session: SwipeDeckSessionRecord,
        cards: List<SwipeDeckSessionCardRecord>,
    )

    suspend fun findDeckSession(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionRecord?

    suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID>

    suspend fun findDeckSessionState(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionState? =
        findDeckSession(sessionId, viewerUserId)?.let { session ->
            SwipeDeckSessionState(
                session = session,
                targetUserIds = listDeckSessionTargetIds(session.sessionId),
            )
        }

    suspend fun isDeckCardActionable(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant,
    ): Boolean

    suspend fun markDeckCardActionRecorded(
        sessionId: UUID,
        cardTokenHash: String,
        targetUserId: UUID,
        action: String,
        now: Instant,
    )

    suspend fun isActionTargetEligible(
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean = false,
    ): Boolean

    /**
     * One-shot precondition gate for action submit. Combines the deck-card actionability
     * check and the target eligibility check that submitAction used to call separately.
     * Implementations should include block/privacy/visibility exclusions in targetEligible.
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
        now: Instant,
    ): SwipeActionPreconditionCheck =
        SwipeActionPreconditionCheck(
            deckCardActionable =
                isDeckCardActionable(
                    sessionId = sessionId,
                    cardTokenHash = cardTokenHash,
                    viewerUserId = viewerUserId,
                    targetUserId = targetUserId,
                    now = now,
                ),
            targetEligible =
                isActionTargetEligible(
                    viewerUserId = viewerUserId,
                    targetUserId = targetUserId,
                    radiusMeters = radiusMeters,
                    requireIntroNotesAllowed = requireIntroNotesAllowed,
                ),
        )

    suspend fun findSwipeActionById(
        actionId: UUID,
        actorUserId: UUID,
    ): SwipeActionRecord?

    suspend fun findLatestRewindableAction(
        viewerUserId: UUID,
        radiusMeters: Int,
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
        consumedInventoryId: UUID? = null,
        /**
         * `travel_locations.id` of the actor's active travel row at the moment
         * this action was recorded, or null if the action was taken outside
         * travel mode. Persisted so the match-creation query can enforce the
         * 24h Like tail (SWIPE_IMPLEMENTATION_SPEC L353): a travel-mode Like
         * only counts for matching while the session is active or within 24h
         * after it ends.
         */
        travelLocationId: UUID? = null,
        matchIdFactory: () -> UUID = UUID::randomUUID,
    ): SwipeActionResult

    suspend fun recordSwipeActionAndMarkCard(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        sessionId: UUID,
        cardTokenHash: String,
        now: Instant,
        noteText: String? = null,
        consumedInventoryId: UUID? = null,
        travelLocationId: UUID? = null,
        matchIdFactory: () -> UUID = UUID::randomUUID,
    ): SwipeActionResult {
        val result =
            recordSwipeAction(
                actionId = actionId,
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                action = action,
                noteText = noteText,
                consumedInventoryId = consumedInventoryId,
                travelLocationId = travelLocationId,
                matchIdFactory = matchIdFactory,
            )
        if (result.recorded) {
            markDeckCardActionRecorded(
                sessionId = sessionId,
                cardTokenHash = cardTokenHash,
                targetUserId = targetUserId,
                action = action,
                now = now,
            )
        }
        return result
    }

    /**
     * Reverses a previously-recorded swipe action. If the action consumed a
     * Pineapple Like (`consumed_inventory_id IS NOT NULL`) the implementation
     * MUST refund the matching `premium_inventory` row atomically inside the
     * same transaction as the action's rewound_at update — `SwipeService.rewind`
     * relies on this to keep inventory accounting consistent.
     */
    suspend fun rewindSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        session: SwipeDeckSessionRecord,
        card: SwipeDeckSessionCardRecord,
        rewoundAt: Instant,
    ): Boolean

    suspend fun listMatches(
        viewerUserId: UUID,
        limit: Int,
    ): List<MatchSummaryRecord>
}

data class SwipeDeckResult(
    val viewerHadLocation: Boolean,
    val cards: List<SwipeCardRecord>,
)

data class SwipeActionPreconditionCheck(
    val deckCardActionable: Boolean,
    val targetEligible: Boolean,
    val viewerTravelLocationId: UUID? = null,
)

data class SwipeDeckSessionState(
    val session: SwipeDeckSessionRecord,
    val targetUserIds: Set<UUID>,
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
    val expiresAt: Instant,
)

data class SwipeDeckSessionCardRecord(
    val cardId: UUID,
    val sessionId: UUID,
    val viewerUserId: UUID,
    val targetUserId: UUID,
    val cardTokenHash: String,
    val position: Int,
    val dealtAt: Instant,
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
    val verified: Boolean = false,
    /**
     * Target has an active travel_locations row. Surfaced as a "Visiting" pill
     * on the swipe card (SWIPE_IMPLEMENTATION_SPEC L348) so the viewer knows
     * the person is browsing their area in passport mode, not local.
     */
    val visiting: Boolean = false,
)

data class SwipeActionResult(
    val recorded: Boolean,
    val match: MatchRecord?,
    val newlyRecorded: Boolean = recorded,
)

data class SwipeActionRecord(
    val actionId: UUID,
    val actorUserId: UUID,
    val targetUserId: UUID,
    val action: String,
    val noteText: String?,
    val createdAt: Instant,
    val rewoundAt: Instant?,
    val match: MatchRecord?,
)

data class SwipeRewindCandidateRecord(
    val actionId: UUID,
    val targetUserId: UUID,
    val action: String,
    val card: SwipeCardRecord,
    /**
     * `premium_inventory.id` decremented when this action was recorded, or null
     * if no inventory was consumed (Likes/Passes never consume). When non-null,
     * the rewind must refund this row atomically with the action rollback.
     */
    val consumedInventoryId: UUID? = null,
)

data class MatchRecord(
    val matchId: UUID,
    val userA: UUID,
    val userB: UUID,
    val createdAt: Instant,
)

data class MatchSummaryRecord(
    val matchId: UUID,
    val matchedUserId: UUID,
    val matchedDisplayName: String?,
    val matchedThumbnailMediaAssetId: UUID?,
    val createdAt: Instant,
)
