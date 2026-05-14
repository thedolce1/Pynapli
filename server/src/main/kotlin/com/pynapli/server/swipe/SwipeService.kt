package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.MatchesListResponseDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SubscriptionTier
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SwipeService(
    private val repository: SwipeRepository,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null,
    private val notificationPublisher: NotificationPublisher? = null,
    private val rateLimitService: RateLimitService? = null,
    private val riskPolicyService: RiskPolicyService? = null,
    private val subscriptionService: SubscriptionService? = null,
    private val deckTokenCodec: SwipeDeckTokenCodec = SwipeDeckTokenCodec(),
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun queryDeck(viewerUserId: UUID, request: SwipeDeckRequestDto): SwipeDeckResponseDto {
        val radiusMeters = request.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        val filters = discoveryFiltersFrom(
            minimumAge = request.minimumAge,
            maximumAge = request.maximumAge,
            relationshipContexts = request.relationshipContexts,
            linkedOnly = request.linkedOnly
        ) { _ -> SwipeException("INVALID_SWIPE_FILTER") }
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        if (filters.hasAdvancedFilters && !policy.advancedFiltersAllowed) {
            throw SwipeException("ENTITLEMENT_REQUIRED")
        }
        val now = clock.instant()
        val scopeHash = SwipeDeckTokenCodec.deckScopeHash(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit,
            filters = filters
        )
        val cursor = request.cursor?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
            try {
                deckTokenCodec.decodeCursor(token, viewerUserId, now)
            } catch (error: SwipeDeckTokenException) {
                throw SwipeException(error.code)
            }
        }
        if (cursor != null && cursor.scopeHash != scopeHash) {
            throw SwipeException(SwipeDeckTokenCodec.INVALID_CURSOR)
        }
        val existingSession = cursor?.let { decoded ->
            val stored = repository.findDeckSession(decoded.sessionId, viewerUserId)
                ?: throw SwipeException(SwipeDeckTokenCodec.INVALID_CURSOR)
            if (
                stored.deckSessionTokenHash != decoded.deckSessionTokenHash ||
                stored.scopeHash != decoded.scopeHash ||
                !stored.expiresAt.isAfter(now)
            ) {
                throw SwipeException(
                    if (!stored.expiresAt.isAfter(now)) {
                        SwipeDeckTokenCodec.STALE_DECK_SESSION
                    } else {
                        SwipeDeckTokenCodec.INVALID_CURSOR
                    }
                )
            }
            stored
        }
        val deckSession = existingSession?.toIssuedDeckSession() ?: deckTokenCodec.issueDeckSession(
            viewerUserId = viewerUserId,
            scopeHash = scopeHash,
            now = now
        )
        val excludedTargetUserIds = existingSession?.let { repository.listDeckSessionTargetIds(it.sessionId) }.orEmpty()
        val startPosition = cursor?.nextPosition ?: 0

        val result = repository.queryDeck(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit + 1,
            filters = filters,
            excludedTargetUserIds = excludedTargetUserIds
        )
        if (!result.viewerHadLocation) throw SwipeException("LOCATION_REQUIRED")

        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
        val visible = if (blockedRelatedIds.isEmpty()) {
            result.cards
        } else {
            result.cards.filterNot { it.userId in blockedRelatedIds }
        }
        val pageCards = visible.take(limit)
        val pageItems = pageCards.mapIndexed { index, card ->
            val position = startPosition + index
            val cardToken = deckTokenCodec.issueCardToken(
                deckSessionId = deckSession.deckSessionId,
                viewerUserId = viewerUserId,
                targetUserId = card.userId,
                scopeHash = scopeHash,
                generatedAt = deckSession.generatedAt,
                expiresAt = deckSession.expiresAt,
                position = position
            )
            SwipeDeckPageItem(
                dto = card.toDto(rankToken = cardToken),
                persistedCard = SwipeDeckSessionCardRecord(
                    cardId = UUID.randomUUID(),
                    sessionId = deckSession.sessionId,
                    viewerUserId = viewerUserId,
                    targetUserId = card.userId,
                    cardTokenHash = deckTokenCodec.tokenHash(cardToken),
                    position = position,
                    dealtAt = now
                )
            )
        }
        repository.upsertDeckSession(
            session = deckSession.toSessionRecord(
                viewerUserId = viewerUserId,
                scopeHash = scopeHash,
                radiusMeters = radiusMeters,
                pageLimit = limit,
                filterPayload = filters.toPersistedJson()
            ),
            cards = pageItems.map { it.persistedCard }
        )
        val nextCursor = if (visible.size > limit) {
            deckTokenCodec.issueCursor(
                deckSession = deckSession,
                viewerUserId = viewerUserId,
                scopeHash = scopeHash,
                nextPosition = startPosition + pageItems.size
            )
        } else {
            null
        }

        return SwipeDeckResponseDto(
            deckSessionId = deckSession.deckSessionId,
            generatedAt = deckSession.generatedAt.toString(),
            items = pageItems.map { it.dto },
            nextCursor = nextCursor,
            adSupported = policy.adSupported,
            advancedFiltersApplied = filters.hasAdvancedFilters
        )
    }

    suspend fun submitAction(viewerUserId: UUID, request: SwipeActionRequestDto): SwipeActionResponseDto {
        val targetUserId = request.targetUserId.toUuidOrThrow()
        if (targetUserId == viewerUserId) throw SwipeException("INVALID_SWIPE_TARGET")

        val action = request.action.trim().lowercase()
        if (action !in ACTIONS) throw SwipeException("INVALID_SWIPE_ACTION")
        val noteText = request.noteText?.trim()
        if (action == SwipeAction.INTRO_NOTE) {
            if (noteText.isNullOrEmpty()) throw SwipeException("INTRO_NOTE_EMPTY")
            if (noteText.length > INTRO_NOTE_MAX_LENGTH) throw SwipeException("INTRO_NOTE_TOO_LONG")
        } else if (!noteText.isNullOrEmpty()) {
            throw SwipeException("INVALID_SWIPE_ACTION")
        }
        val deckSessionId = request.deckSessionId.trim()
        if (deckSessionId.isEmpty()) throw SwipeException(SwipeDeckTokenCodec.INVALID_DECK_SESSION)
        val cardToken = request.cardToken.trim()
        if (cardToken.isEmpty()) throw SwipeException(SwipeDeckTokenCodec.INVALID_CARD_TOKEN)
        val now = clock.instant()
        val actionTokens = try {
            deckTokenCodec.validateAction(
                deckSessionId = deckSessionId,
                cardToken = cardToken,
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                now = now
            )
        } catch (error: SwipeDeckTokenException) {
            throw SwipeException(error.code)
        }
        if (!repository.isDeckCardActionable(
                sessionId = actionTokens.sessionId,
                cardTokenHash = actionTokens.cardTokenHash,
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                now = now
            )
        ) {
            throw SwipeException(SwipeDeckTokenCodec.INVALID_CARD_TOKEN)
        }
        if (riskPolicyService?.isSocialActionRestricted(viewerUserId) == true) {
            throw SwipeException("RISK_RESTRICTED")
        }
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        if (action == SwipeAction.INTRO_NOTE) {
            if (policy.tier == SubscriptionTier.FREE) {
                throw SwipeException("ENTITLEMENT_REQUIRED")
            }
        }

        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) {
            throw SwipeException("INVALID_SWIPE_TARGET")
        }
        if (rateLimitService?.check(viewerUserId, RateLimitedAction.SWIPE_ACTION)?.allowed == false) {
            throw SwipeException("RATE_LIMITED")
        }

        if (!repository.isActionTargetEligible(
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                radiusMeters = MAX_RADIUS_METERS,
                requireIntroNotesAllowed = action == SwipeAction.INTRO_NOTE
            )
        ) {
            throw SwipeException("INVALID_SWIPE_TARGET")
        }

        if (action == SwipeAction.INTRO_NOTE) {
            val consumed = subscriptionService?.consumeIntroNoteInventory(viewerUserId) ?: true
            if (!consumed) throw SwipeException("INTRO_NOTE_INVENTORY_REQUIRED")
        }
        if (action == SwipeAction.PINEAPPLE_LIKE) {
            val consumed = subscriptionService?.consumePineappleLikeInventory(viewerUserId) ?: true
            if (!consumed) throw SwipeException("PINEAPPLE_LIKE_INVENTORY_REQUIRED")
        }

        val result = repository.recordSwipeAction(
            actorUserId = viewerUserId,
            targetUserId = targetUserId,
            action = action,
            noteText = noteText
        )
        if (result.recorded) {
            repository.markDeckCardActionRecorded(
                sessionId = actionTokens.sessionId,
                cardTokenHash = actionTokens.cardTokenHash,
                targetUserId = targetUserId,
                action = action,
                now = clock.instant()
            )
        }
        val match = result.match
        if (match != null) {
            notificationPublisher?.enqueue(
                NotificationEventDraft(
                    recipientUserId = targetUserId,
                    actorUserId = viewerUserId,
                    kind = NotificationKind.MATCH,
                    subjectId = match.matchId,
                    idempotencyKey = "match:${match.matchId}:$targetUserId",
                    payload = mapOf(
                        "matchId" to match.matchId.toString(),
                        "matchedUserId" to viewerUserId.toString()
                    )
                )
            )
        }

        return SwipeActionResponseDto(
            recorded = result.recorded,
            match = match?.let { record ->
                MatchDto(
                    matchId = record.matchId.toString(),
                    matchedUserId = if (record.userA == viewerUserId) record.userB.toString() else record.userA.toString(),
                    matchedDisplayName = null,
                    matchedPhotoUrl = null,
                    createdAt = record.createdAt.toString()
                )
            }
        )
    }

    suspend fun listMatches(viewerUserId: UUID): MatchesListResponseDto {
        val rows = repository.listMatches(viewerUserId, MATCHES_LIMIT)
        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
        val blockVisible = if (blockedRelatedIds.isEmpty()) {
            rows
        } else {
            rows.filterNot { it.matchedUserId in blockedRelatedIds }
        }
        val visible = if (riskPolicyService == null) {
            blockVisible
        } else {
            buildList {
                for (record in blockVisible) {
                    if (!riskPolicyService.shouldHideFromDiscovery(record.matchedUserId)) {
                        add(record)
                    }
                }
            }
        }
        return MatchesListResponseDto(
            matches = visible.map { record ->
                MatchDto(
                    matchId = record.matchId.toString(),
                    matchedUserId = record.matchedUserId.toString(),
                    matchedDisplayName = record.matchedDisplayName,
                    matchedPhotoUrl = record.matchedThumbnailMediaAssetId
                        ?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                    createdAt = record.createdAt.toString()
                )
            }
        )
    }

    private fun SwipeCardRecord.toDto(rankToken: String): SwipeCardDto = SwipeCardDto(
        userId = userId.toString(),
        displayName = displayName,
        displayAge = displayAge,
        distanceLabel = if (showDistance) distanceMeters.toPublicDistanceLabel() else null,
        distanceMeters = if (showDistance) distanceMeters.toPublicDistanceMeters() else null,
        photoUrl = thumbnailMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
        linkedPartner = linkedPartnerUserId?.let { partnerUserId ->
            LinkedPartnerMiniDto(
                userId = partnerUserId.toString(),
                displayName = linkedPartnerDisplayName,
                thumbnailUrl = linkedPartnerThumbnailMediaAssetId
                    ?.let { mediaAssetUrlResolver?.publicContentUrl(it) }
            )
        },
        rankToken = rankToken
    )

    private fun IssuedSwipeDeckSession.toSessionRecord(
        viewerUserId: UUID,
        scopeHash: String,
        radiusMeters: Int,
        pageLimit: Int,
        filterPayload: String
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
            expiresAt = expiresAt
        )

    private fun SwipeDeckSessionRecord.toIssuedDeckSession(): IssuedSwipeDeckSession =
        IssuedSwipeDeckSession(
            sessionId = sessionId,
            deckSessionId = deckSessionToken,
            deckSessionTokenHash = deckSessionTokenHash,
            generatedAt = generatedAt,
            expiresAt = expiresAt
        )

    private fun DiscoveryFilters.toPersistedJson(): String =
        buildString {
            append("{")
            append("\"minimumAge\":").append(minimumAge?.toString() ?: "null")
            append(",\"maximumAge\":").append(maximumAge?.toString() ?: "null")
            append(",\"linkedOnly\":").append(linkedOnly?.toString() ?: "null")
            append(",\"relationshipContexts\":").append(relationshipContexts.toJsonArray())
            append(",\"verifiedOnly\":").append(verifiedOnly)
            append(",\"facePhotoVerifiedOnly\":").append(facePhotoVerifiedOnly)
            append(",\"ageVerifiedOnly\":").append(ageVerifiedOnly)
            append(",\"discoveryStyles\":").append(discoveryStyles.toJsonArray())
            append(",\"lifestyleTags\":").append(lifestyleTags.toJsonArray())
            append(",\"logisticsTags\":").append(logisticsTags.toJsonArray())
            append(",\"tonightAvailableOnly\":").append(tonightAvailableOnly)
            append(",\"intents\":").append(intents.toJsonArray())
            append("}")
        }

    private fun Set<String>.toJsonArray(): String =
        sorted().joinToString(prefix = "[", postfix = "]") { value ->
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

    private fun String.toUuidOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw SwipeException("INVALID_SWIPE_TARGET") }

    private data class SwipeDeckPageItem(
        val dto: SwipeCardDto,
        val persistedCard: SwipeDeckSessionCardRecord
    )

    companion object {
        private const val MIN_RADIUS_METERS = 1_000
        private const val MAX_RADIUS_METERS = 100_000
        private const val MAX_LIMIT = 60
        private const val MATCHES_LIMIT = 50
        private const val INTRO_NOTE_MAX_LENGTH = 140
        private val ACTIONS = setOf(
            SwipeAction.LIKE,
            SwipeAction.PASS,
            SwipeAction.PINEAPPLE_LIKE,
            SwipeAction.INTRO_NOTE
        )
    }
}

class SwipeException(val code: String) : RuntimeException(code)
