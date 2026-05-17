package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.profiling.profile
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
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
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
    private val presenceService: PresenceService? = null,
    private val deckTokenCodec: SwipeDeckTokenCodec = SwipeDeckTokenCodec(),
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun queryDeck(viewerUserId: UUID, request: SwipeDeckRequestDto): SwipeDeckResponseDto {
        val radiusMeters = request.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        // FILTERS_SPEC L356-365: dealbreaker keys must be on the allow-list; unknown keys
        // are rejected so clients cannot smuggle in unrecognized hard-gate semantics.
        // B3 will use this validated set to decide which filters can be relaxed when
        // recommendations run low (L373); for B2 the validation is the only enforcement.
        request.dealbreakers.forEach { key ->
            if (key.trim() !in SwipeDealbreakerKey.ALL) throw SwipeException("INVALID_SWIPE_FILTER")
        }
        val filters = discoveryFiltersFrom(
            minimumAge = request.minimumAge,
            maximumAge = request.maximumAge,
            relationshipContexts = request.relationshipContexts,
            linkedOnly = request.linkedOnly,
            verifiedOnly = request.verifiedOnly,
            facePhotoVerifiedOnly = request.facePhotoVerifiedOnly,
            ageVerifiedOnly = request.ageVerifiedOnly,
            discoveryStyles = request.discoveryStyles,
            lifestyleTags = request.lifestyleTags,
            logisticsTags = request.logisticsTags,
            intents = request.intents,
            verifiedPreferred = request.verifiedPreferred
        ) { _ -> SwipeException("INVALID_SWIPE_FILTER") }
        val policy = profile("swipe.deck.policy") {
            subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        }
        // FILTERS_SPEC L432-440: free tier keeps Distance, Age, Singles/Linked/Everyone,
        // Solo/together/social basics, and Verified basics. Only the lifestyle taxonomy
        // and the advanced logistics tags require Social/Select entitlement. Mirrors
        // GridService's gate (uses the same `requiresAdvancedEntitlement` predicate).
        if (filters.requiresAdvancedEntitlement && !policy.advancedFiltersAllowed) {
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
            val stored = profile("swipe.deck.session.find") {
                repository.findDeckSession(decoded.sessionId, viewerUserId)
            } ?: throw SwipeException(SwipeDeckTokenCodec.INVALID_CURSOR)
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
        val excludedTargetUserIds = profile("swipe.deck.session.targets") {
            existingSession?.let { repository.listDeckSessionTargetIds(it.sessionId) }.orEmpty()
        }
        val startPosition = cursor?.nextPosition ?: 0

        val firstPass = profile("swipe.deck.repo.queryDeck") {
            repository.queryDeck(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit + 1,
                filters = filters,
                excludedTargetUserIds = excludedTargetUserIds
            )
        }
        // FILTERS_SPEC L373 + L403-409: if the first-pass result is small AND some filters
        // are relaxable (i.e., not in the dealbreaker set), retry with non-dealbreaker
        // fields cleared / radius widened. Dealbreakers stay set in both passes.
        val dealbreakerSet = request.dealbreakers.mapTo(mutableSetOf()) { it.trim() }
        val lowWaterMark = (limit / 3).coerceAtLeast(MIN_RELAXATION_THRESHOLD)
        val result = if (
            firstPass.viewerHadLocation &&
            firstPass.cards.size < lowWaterMark &&
            hasRelaxableFilters(filters, radiusMeters, dealbreakerSet)
        ) {
            val relaxedFilters = relaxFilters(filters, dealbreakerSet)
            val relaxedRadius = relaxRadius(radiusMeters, dealbreakerSet)
            val alreadySeen = excludedTargetUserIds + firstPass.cards.mapTo(mutableSetOf()) { it.userId }
            val secondPass = profile("swipe.deck.repo.queryDeckRelaxed") {
                repository.queryDeck(
                    viewerUserId = viewerUserId,
                    radiusMeters = relaxedRadius,
                    limit = limit + 1,
                    filters = relaxedFilters,
                    excludedTargetUserIds = alreadySeen
                )
            }
            // First-pass cards stay at the head of the deck (best match); relaxed cards
            // append behind them. distinctBy{} guards against any overlap that slipped
            // through the excludedIds filter (e.g., session race).
            val merged = (firstPass.cards + secondPass.cards).distinctBy { it.userId }
            firstPass.copy(cards = merged)
        } else {
            firstPass
        }
        if (!result.viewerHadLocation) throw SwipeException("LOCATION_REQUIRED")

        val blockedRelatedIds = profile("swipe.deck.blocks") {
            blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
        }
        val visible = if (blockedRelatedIds.isEmpty()) {
            result.cards
        } else {
            result.cards.filterNot { it.userId in blockedRelatedIds }
        }
        val pageCards = visible.take(limit)
        val presenceLookupIds = buildSet {
            pageCards.forEach { card ->
                add(card.userId)
                card.linkedPartnerUserId?.let { add(it) }
            }
        }
        val onlineUserIds = profile("swipe.deck.presence") {
            onlineUserIdsFor(presenceLookupIds)
        }
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
                dto = card.toDto(rankToken = cardToken, online = card.userId in onlineUserIds, onlineUserIds = onlineUserIds),
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
        profile("swipe.deck.session.upsert") {
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
        }
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
            advancedFiltersApplied = filters.requiresAdvancedEntitlement
        )
    }

    suspend fun submitAction(viewerUserId: UUID, request: SwipeActionRequestDto): SwipeActionResponseDto {
        val targetUserId = request.targetUserId.toUuidOrThrow()
        if (targetUserId == viewerUserId) throw SwipeException("INVALID_SWIPE_TARGET")

        val action = request.action.trim().lowercase()
        if (action !in ACTIONS) throw SwipeException("INVALID_SWIPE_ACTION")
        val noteText = request.noteText?.trim()?.takeIf { it.isNotEmpty() }
        if (action == SwipeAction.INTRO_NOTE) {
            if (noteText == null) throw SwipeException("INTRO_NOTE_EMPTY")
            if (noteText.length > INTRO_NOTE_MAX_LENGTH) throw SwipeException("INTRO_NOTE_TOO_LONG")
        } else if (noteText != null) {
            throw SwipeException("INVALID_SWIPE_ACTION")
        }
        val actionId = request.actionId.trim().toActionIdOrThrow()
        val existingAction = profile("swipe.action.idempotency") {
            repository.findSwipeActionById(actionId, viewerUserId)
        }
        if (existingAction != null) {
            if (
                existingAction.targetUserId != targetUserId ||
                existingAction.action != action ||
                existingAction.noteText != noteText
            ) {
                throw SwipeException("INVALID_SWIPE_ACTION_ID")
            }
            if (existingAction.rewoundAt != null) {
                return SwipeActionResponseDto(recorded = false)
            }
            return existingAction.toResponse(viewerUserId)
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
        // Combined deck-card-actionable + target-eligibility check in one DB round-trip.
        // Both queries previously hit overlapping joins; folding them saves a Hikari acquire
        // and a second statement parse on every action submit.
        val preconditions = profile("swipe.action.preconditions") {
            repository.checkActionPreconditions(
                sessionId = actionTokens.sessionId,
                cardTokenHash = actionTokens.cardTokenHash,
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                radiusMeters = MAX_RADIUS_METERS,
                requireIntroNotesAllowed = action == SwipeAction.INTRO_NOTE,
                now = now
            )
        }
        if (!preconditions.deckCardActionable) {
            throw SwipeException(SwipeDeckTokenCodec.INVALID_CARD_TOKEN)
        }
        if (profile("swipe.action.risk") { riskPolicyService?.isSocialActionRestricted(viewerUserId) == true }) {
            throw SwipeException("RISK_RESTRICTED")
        }
        val policy = profile("swipe.action.policy") {
            subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        }
        if (action == SwipeAction.INTRO_NOTE) {
            if (policy.tier == SubscriptionTier.FREE) {
                throw SwipeException("ENTITLEMENT_REQUIRED")
            }
        }

        if (profile("swipe.action.blocks") { blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true }) {
            throw SwipeException("INVALID_SWIPE_TARGET")
        }
        if (profile("swipe.action.rateLimit") {
                rateLimitService?.check(viewerUserId, RateLimitedAction.SWIPE_ACTION)?.allowed == false
            }) {
            throw SwipeException("RATE_LIMITED")
        }

        if (!preconditions.targetEligible) {
            throw SwipeException("INVALID_SWIPE_TARGET")
        }

        if (action == SwipeAction.INTRO_NOTE) {
            val consumed = profile("swipe.action.inventory.intro") {
                subscriptionService?.consumeIntroNoteInventory(viewerUserId) ?: true
            }
            if (!consumed) throw SwipeException("INTRO_NOTE_INVENTORY_REQUIRED")
        }
        // For Pineapple Like the consume call returns the `premium_inventory.id`
        // that was decremented. We thread that id into `recordSwipeAction` so the
        // rewind path can later refund the exact row atomically. If no subscription
        // service is wired (test seam), we treat the action as consumed but leave
        // the inventory id null — refund-on-rewind becomes a no-op.
        val pineappleInventoryId: UUID? = if (action == SwipeAction.PINEAPPLE_LIKE) {
            val consumedInventoryId = profile("swipe.action.inventory.pineapple") {
                if (subscriptionService == null) null
                else subscriptionService.consumePineappleLikeInventoryReturningId(viewerUserId)
                    ?: throw SwipeException("PINEAPPLE_LIKE_INVENTORY_REQUIRED")
            }
            consumedInventoryId
        } else {
            null
        }

        val result = profile("swipe.action.record") {
            repository.recordSwipeAction(
                actionId = actionId,
                actorUserId = viewerUserId,
                targetUserId = targetUserId,
                action = action,
                noteText = noteText,
                consumedInventoryId = pineappleInventoryId
            )
        }
        if (result.recorded) {
            profile("swipe.action.markCard") {
                repository.markDeckCardActionRecorded(
                    sessionId = actionTokens.sessionId,
                    cardTokenHash = actionTokens.cardTokenHash,
                    targetUserId = targetUserId,
                    action = action,
                    now = clock.instant()
                )
            }
        }
        val match = result.match
        if (match != null) {
            profile("swipe.action.notify") {
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

    suspend fun rewind(viewerUserId: UUID): SwipeRewindResponseDto {
        if (profile("swipe.rewind.risk") { riskPolicyService?.isSocialActionRestricted(viewerUserId) == true }) {
            throw SwipeException("RISK_RESTRICTED")
        }
        val policy = profile("swipe.rewind.policy") {
            subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        }
        if (policy.tier == SubscriptionTier.FREE) {
            throw SwipeException("ENTITLEMENT_REQUIRED")
        }
        if (profile("swipe.rewind.rateLimit") {
                rateLimitService?.check(viewerUserId, RateLimitedAction.SWIPE_ACTION)?.allowed == false
            }) {
            throw SwipeException("RATE_LIMITED")
        }

        val candidate = profile("swipe.rewind.findCandidate") {
            repository.findLatestRewindableAction(
                viewerUserId = viewerUserId,
                radiusMeters = MAX_RADIUS_METERS
            )
        } ?: return SwipeRewindResponseDto(rewound = false)
        if (profile("swipe.rewind.blocks") {
                blockRepository?.isBlockedEitherWay(viewerUserId, candidate.targetUserId) == true
            }) {
            return SwipeRewindResponseDto(rewound = false)
        }

        val now = clock.instant()
        val filters = DiscoveryFilters()
        val scopeHash = SwipeDeckTokenCodec.deckScopeHash(
            viewerUserId = viewerUserId,
            radiusMeters = MAX_RADIUS_METERS,
            limit = 1,
            filters = filters
        )
        val deckSession = deckTokenCodec.issueDeckSession(
            viewerUserId = viewerUserId,
            scopeHash = scopeHash,
            now = now
        )
        val cardToken = deckTokenCodec.issueCardToken(
            deckSessionId = deckSession.deckSessionId,
            viewerUserId = viewerUserId,
            targetUserId = candidate.targetUserId,
            scopeHash = scopeHash,
            generatedAt = deckSession.generatedAt,
            expiresAt = deckSession.expiresAt,
            position = 0
        )
        val rewound = profile("swipe.rewind.record") {
            repository.rewindSwipeAction(
                actionId = candidate.actionId,
                actorUserId = viewerUserId,
                targetUserId = candidate.targetUserId,
                session = deckSession.toSessionRecord(
                    viewerUserId = viewerUserId,
                    scopeHash = scopeHash,
                    radiusMeters = MAX_RADIUS_METERS,
                    pageLimit = 1,
                    filterPayload = filters.toPersistedJson()
                ),
                card = SwipeDeckSessionCardRecord(
                    cardId = UUID.randomUUID(),
                    sessionId = deckSession.sessionId,
                    viewerUserId = viewerUserId,
                    targetUserId = candidate.targetUserId,
                    cardTokenHash = deckTokenCodec.tokenHash(cardToken),
                    position = 0,
                    dealtAt = now
                ),
                rewoundAt = now
            )
        }

        return if (rewound) {
            val presenceLookupIds = buildSet {
                add(candidate.targetUserId)
                candidate.card.linkedPartnerUserId?.let { add(it) }
            }
            val online = profile("swipe.rewind.presence") {
                onlineUserIdsFor(presenceLookupIds)
            }
            SwipeRewindResponseDto(
                rewound = true,
                deckSessionId = deckSession.deckSessionId,
                card = candidate.card.toDto(
                    rankToken = cardToken,
                    online = candidate.targetUserId in online,
                    onlineUserIds = online
                )
            )
        } else {
            SwipeRewindResponseDto(rewound = false)
        }
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

    private suspend fun onlineUserIdsFor(userIds: Collection<UUID>): Set<UUID> {
        val service = presenceService ?: return emptySet()
        if (userIds.isEmpty()) return emptySet()
        return runCatching { service.onlineUserIds(userIds) }.getOrDefault(emptySet())
    }

    private fun SwipeCardRecord.toDto(
        rankToken: String,
        online: Boolean,
        onlineUserIds: Set<UUID>
    ): SwipeCardDto {
        val resolver = mediaAssetUrlResolver
        val resolvedPhotoUrls = if (resolver == null) {
            emptyList()
        } else {
            val sourceIds = if (thumbnailMediaAssetIds.isNotEmpty()) {
                thumbnailMediaAssetIds
            } else {
                listOfNotNull(thumbnailMediaAssetId)
            }
            sourceIds.take(MAX_VISIBLE_PHOTOS).mapNotNull { resolver.publicContentUrl(it) }
        }
        return SwipeCardDto(
            userId = userId.toString(),
            displayName = displayName,
            displayAge = displayAge,
            distanceLabel = if (showDistance) distanceMeters.toPublicDistanceLabel() else null,
            distanceMeters = if (showDistance) distanceMeters.toPublicDistanceMeters() else null,
            photoUrl = resolvedPhotoUrls.firstOrNull(),
            photoUrls = resolvedPhotoUrls,
            linkedPartner = linkedPartnerUserId?.let { partnerUserId ->
                LinkedPartnerMiniDto(
                    userId = partnerUserId.toString(),
                    displayName = linkedPartnerDisplayName,
                    thumbnailUrl = linkedPartnerThumbnailMediaAssetId
                        ?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                    online = partnerUserId in onlineUserIds
                )
            },
            rankToken = rankToken,
            online = online,
            shortBio = bio.toShortBio(),
            tags = tags.toVisibleTags(),
            verified = verified
        )
    }

    private fun String?.toShortBio(): String? {
        val trimmed = this?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= MAX_SHORT_BIO_CHARS) trimmed else trimmed.take(MAX_SHORT_BIO_CHARS)
    }

    private fun hasRelaxableFilters(
        filters: DiscoveryFilters,
        radiusMeters: Int,
        dealbreakers: Set<String>
    ): Boolean {
        if (SwipeDealbreakerKey.AGE !in dealbreakers &&
            (filters.minimumAge != null || filters.maximumAge != null)) return true
        if (SwipeDealbreakerKey.VERIFIED !in dealbreakers &&
            (filters.verifiedOnly || filters.facePhotoVerifiedOnly || filters.ageVerifiedOnly)) return true
        if (SwipeDealbreakerKey.DISCOVERY_STYLES !in dealbreakers &&
            filters.discoveryStyles.isNotEmpty()) return true
        if (SwipeDealbreakerKey.INTENTS !in dealbreakers &&
            filters.intents.isNotEmpty()) return true
        if (SwipeDealbreakerKey.LINKED_ONLY !in dealbreakers &&
            filters.linkedOnly != null) return true
        // relationshipContexts, lifestyleTags, logisticsTags are always relaxable
        // (not in the dealbreaker allow-list per SwipeDealbreakerKey).
        if (filters.relationshipContexts.isNotEmpty()) return true
        if (filters.lifestyleTags.isNotEmpty()) return true
        if (filters.logisticsTags.isNotEmpty()) return true
        if (SwipeDealbreakerKey.DISTANCE !in dealbreakers &&
            radiusMeters < MAX_RADIUS_METERS) return true
        return false
    }

    private fun relaxFilters(
        filters: DiscoveryFilters,
        dealbreakers: Set<String>
    ): DiscoveryFilters = filters.copy(
        minimumAge = if (SwipeDealbreakerKey.AGE in dealbreakers) filters.minimumAge else null,
        maximumAge = if (SwipeDealbreakerKey.AGE in dealbreakers) filters.maximumAge else null,
        relationshipContexts = emptySet(),
        linkedOnly = if (SwipeDealbreakerKey.LINKED_ONLY in dealbreakers) filters.linkedOnly else null,
        verifiedOnly = if (SwipeDealbreakerKey.VERIFIED in dealbreakers) filters.verifiedOnly else false,
        facePhotoVerifiedOnly = if (SwipeDealbreakerKey.VERIFIED in dealbreakers) filters.facePhotoVerifiedOnly else false,
        ageVerifiedOnly = if (SwipeDealbreakerKey.VERIFIED in dealbreakers) filters.ageVerifiedOnly else false,
        discoveryStyles = if (SwipeDealbreakerKey.DISCOVERY_STYLES in dealbreakers) filters.discoveryStyles else emptySet(),
        intents = if (SwipeDealbreakerKey.INTENTS in dealbreakers) filters.intents else emptySet(),
        lifestyleTags = emptySet(),
        logisticsTags = emptySet()
    )

    private fun relaxRadius(radiusMeters: Int, dealbreakers: Set<String>): Int =
        if (SwipeDealbreakerKey.DISTANCE in dealbreakers) radiusMeters
        else (radiusMeters * 2).coerceAtMost(MAX_RADIUS_METERS)

    private fun List<String>.toVisibleTags(): List<String> {
        if (isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        val display = mutableListOf<String>()
        for (raw in this) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase()
            if (seen.add(key)) {
                display += trimmed
                if (display.size >= MAX_VISIBLE_TAGS) break
            }
        }
        return display
    }

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

    private fun String.toActionIdOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw SwipeException("INVALID_SWIPE_ACTION_ID") }

    private fun SwipeActionRecord.toResponse(viewerUserId: UUID): SwipeActionResponseDto =
        SwipeActionResponseDto(
            recorded = true,
            match = match?.toDto(viewerUserId)
        )

    private fun MatchRecord.toDto(viewerUserId: UUID): MatchDto =
        MatchDto(
            matchId = matchId.toString(),
            matchedUserId = if (userA == viewerUserId) userB.toString() else userA.toString(),
            matchedDisplayName = null,
            matchedPhotoUrl = null,
            createdAt = createdAt.toString()
        )

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
        private const val MAX_SHORT_BIO_CHARS = 120
        private const val MAX_VISIBLE_TAGS = 3
        private const val MAX_VISIBLE_PHOTOS = 5
        // Below this, the deck is considered "low" and relaxation is attempted on
        // non-dealbreaker filters (FILTERS_SPEC L373 + L403-409). Threshold is the
        // larger of limit/3 and MIN_RELAXATION_THRESHOLD so micro-relaxations on
        // very small page requests still fire when the deck is essentially empty.
        private const val MIN_RELAXATION_THRESHOLD = 3
        private val ACTIONS = setOf(
            SwipeAction.LIKE,
            SwipeAction.PASS,
            SwipeAction.PINEAPPLE_LIKE,
            SwipeAction.INTRO_NOTE
        )
    }
}

class SwipeException(val code: String) : RuntimeException(code)
