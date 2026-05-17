package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.TopPickCardDto
import com.pynapli.shared.network.dto.TopPicksResponseDto
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Top Picks daily refresh (SWIPE_IMPLEMENTATION_SPEC L293-313).
 *
 * Find-or-generate flow keyed by viewer: the same curated set is returned for
 * every read inside the 24h window so the user sees stable picks until expiry.
 * On the next read past expiry a fresh set is generated via the existing swipe
 * ranker with a quality bias (`verifiedPreferred = true`).
 *
 * Card tokens are issued through the same `SwipeDeckTokenCodec` as the live
 * deck, and a backing `swipe_deck_sessions` row is upserted alongside the Top
 * Pick session so the existing `submitAction` flow accepts Pick targets without
 * changes — Picks are a *shortcut into* the swipe engine per spec L313, not a
 * parallel action surface. The `deck_session_id` is persisted on the Top Pick
 * session row so cached reads can deterministically re-issue raw card tokens
 * (the codec is pure HMAC over its inputs) without ever persisting plaintext.
 */
class TopPicksService(
    private val topPicksRepository: TopPicksRepository,
    private val swipeRepository: SwipeRepository,
    private val subscriptionService: SubscriptionService? = null,
    private val presenceService: PresenceService? = null,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val deckTokenCodec: SwipeDeckTokenCodec = SwipeDeckTokenCodec(),
    private val clock: Clock = Clock.systemUTC(),
    private val refreshInterval: Duration = REFRESH_INTERVAL,
    private val pickRadiusMeters: Int = PICK_RADIUS_METERS,
    private val freeTierLimit: Int = FREE_TIER_LIMIT,
    private val paidTierLimit: Int = PAID_TIER_LIMIT,
    private val sessionIdFactory: () -> UUID = UUID::randomUUID,
    private val cardIdFactory: () -> UUID = UUID::randomUUID
) {

    suspend fun getCurrentPicks(viewerUserId: UUID): TopPicksResponseDto {
        val now = clock.instant()
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        val tierLimit = if (policy.tier == SubscriptionTier.FREE) freeTierLimit else paidTierLimit

        val cached = topPicksRepository.findCurrentSession(viewerUserId, now)
        if (cached != null) {
            return cached.toResponse(viewerUserId, tierLimit)
        }
        return generateFreshSession(viewerUserId, now, tierLimit)
    }

    private suspend fun generateFreshSession(
        viewerUserId: UUID,
        now: Instant,
        tierLimit: Int
    ): TopPicksResponseDto {
        // Quality bias per spec L311: verified profiles are ordered first via the
        // existing soft_score ORDER BY. Already-actioned targets are excluded by
        // the swipe SQL itself so Picks never re-show profiles the user passed.
        val filters = DiscoveryFilters(verifiedPreferred = true)
        val deckResult = swipeRepository.queryDeck(
            viewerUserId = viewerUserId,
            radiusMeters = pickRadiusMeters,
            limit = tierLimit,
            filters = filters
        )
        if (!deckResult.viewerHadLocation) throw TopPicksException("LOCATION_REQUIRED")

        val candidates = deckResult.cards.take(tierLimit)
        val scopeHash = SwipeDeckTokenCodec.deckScopeHash(
            viewerUserId = viewerUserId,
            radiusMeters = pickRadiusMeters,
            limit = tierLimit,
            filters = filters
        )
        val deckSession = deckTokenCodec.issueDeckSession(
            viewerUserId = viewerUserId,
            scopeHash = scopeHash,
            now = now
        )
        val expiresAt = now.plus(refreshInterval)

        // Mint raw tokens once; they get returned to the client and the hashes
        // get persisted for server-side validation on submitAction.
        val tokens = candidates.mapIndexed { index, card ->
            val raw = deckTokenCodec.issueCardToken(
                deckSessionId = deckSession.deckSessionId,
                viewerUserId = viewerUserId,
                targetUserId = card.userId,
                scopeHash = scopeHash,
                generatedAt = deckSession.generatedAt,
                expiresAt = deckSession.expiresAt,
                position = index
            )
            CardTokenBinding(
                position = index,
                targetUserId = card.userId,
                rawToken = raw,
                tokenHash = deckTokenCodec.tokenHash(raw),
                cardRecord = card
            )
        }
        val swipeCards = tokens.map { binding ->
            SwipeDeckSessionCardRecord(
                cardId = UUID.randomUUID(),
                sessionId = deckSession.sessionId,
                viewerUserId = viewerUserId,
                targetUserId = binding.targetUserId,
                cardTokenHash = binding.tokenHash,
                position = binding.position,
                dealtAt = now
            )
        }
        swipeRepository.upsertDeckSession(
            session = SwipeDeckSessionRecord(
                sessionId = deckSession.sessionId,
                deckSessionToken = deckSession.deckSessionId,
                deckSessionTokenHash = deckSession.deckSessionTokenHash,
                viewerUserId = viewerUserId,
                scopeHash = scopeHash,
                radiusMeters = pickRadiusMeters,
                pageLimit = tierLimit,
                filterPayload = filters.toPersistedJson(),
                generatedAt = deckSession.generatedAt,
                expiresAt = deckSession.expiresAt
            ),
            cards = swipeCards
        )
        val saved = topPicksRepository.createSession(
            sessionId = sessionIdFactory(),
            userId = viewerUserId,
            deckSessionId = deckSession.sessionId,
            generatedAt = now,
            expiresAt = expiresAt,
            cards = tokens.map { binding ->
                TopPickCardInsert(
                    cardId = cardIdFactory(),
                    targetUserId = binding.targetUserId,
                    position = binding.position,
                    cardTokenHash = binding.tokenHash,
                    dealtAt = now
                )
            }
        )
        val onlineIds = onlineFor(candidates.map { it.userId }.toSet())
        return TopPicksResponseDto(
            sessionId = saved.session.sessionId.toString(),
            generatedAt = saved.session.generatedAt.toString(),
            expiresAt = saved.session.expiresAt.toString(),
            tierLimit = tierLimit,
            picks = tokens.map { binding ->
                TopPickCardDto(
                    position = binding.position,
                    card = binding.cardRecord.toCardDto(
                        rankToken = binding.rawToken,
                        onlineIds = onlineIds
                    )
                )
            }
        )
    }

    private suspend fun TopPickSessionWithCards.toResponse(
        viewerUserId: UUID,
        tierLimit: Int
    ): TopPicksResponseDto {
        if (cards.isEmpty()) {
            return TopPicksResponseDto(
                sessionId = session.sessionId.toString(),
                generatedAt = session.generatedAt.toString(),
                expiresAt = session.expiresAt.toString(),
                picks = emptyList(),
                tierLimit = tierLimit
            )
        }
        // Re-hydrate the cached pick targets via the swipe ranker so the response
        // has fresh display name / distance / verified state. Pull a generous
        // candidate window then filter to the cached target set. If a target is
        // no longer eligible (deleted, blocked, etc.) we drop it from the response
        // but keep the cached session intact — the next refresh past expiry will
        // regenerate.
        val targetIds = cards.map { it.targetUserId }.toSet()
        val deckResult = swipeRepository.queryDeck(
            viewerUserId = viewerUserId,
            radiusMeters = pickRadiusMeters,
            limit = (targetIds.size * 4).coerceAtLeast(targetIds.size),
            filters = DiscoveryFilters(verifiedPreferred = true)
        )
        val byTarget = deckResult.cards.associateBy { it.userId }
        val onlineIds = onlineFor(targetIds)

        // Reissue raw tokens deterministically from the backing swipe_deck_session.
        // The codec is pure HMAC over (deckSessionId, viewer, target, scopeHash,
        // generatedAt, expiresAt, position) — re-running with the same inputs
        // produces the same plaintext token whose hash matches the persisted
        // card_token_hash. We sanity-check that match below.
        val backingSession = swipeRepository.findDeckSession(session.deckSessionId, viewerUserId)
            ?: throw TopPicksException("STALE_TOP_PICK_SESSION")

        return TopPicksResponseDto(
            sessionId = session.sessionId.toString(),
            generatedAt = session.generatedAt.toString(),
            expiresAt = session.expiresAt.toString(),
            tierLimit = tierLimit,
            picks = cards.mapNotNull { record ->
                val candidate = byTarget[record.targetUserId] ?: return@mapNotNull null
                val rawToken = deckTokenCodec.issueCardToken(
                    deckSessionId = backingSession.deckSessionToken,
                    viewerUserId = viewerUserId,
                    targetUserId = record.targetUserId,
                    scopeHash = backingSession.scopeHash,
                    generatedAt = backingSession.generatedAt,
                    expiresAt = backingSession.expiresAt,
                    position = record.position
                )
                // Defense: if the reissued hash doesn't match what's persisted,
                // the codec secret rotated or the migration is partial — skip
                // the card rather than hand a token that would fail submitAction.
                if (deckTokenCodec.tokenHash(rawToken) != record.cardTokenHash) {
                    return@mapNotNull null
                }
                TopPickCardDto(
                    position = record.position,
                    card = candidate.toCardDto(
                        rankToken = rawToken,
                        onlineIds = onlineIds
                    )
                )
            }
        )
    }

    private suspend fun onlineFor(ids: Set<UUID>): Set<UUID> {
        val service = presenceService ?: return emptySet()
        if (ids.isEmpty()) return emptySet()
        return runCatching { service.onlineUserIds(ids) }.getOrDefault(emptySet())
    }

    private fun SwipeCardRecord.toCardDto(
        rankToken: String,
        onlineIds: Set<UUID>
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
                    online = partnerUserId in onlineIds
                )
            },
            rankToken = rankToken,
            online = userId in onlineIds,
            shortBio = bio?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SHORT_BIO_CHARS),
            tags = tags.take(MAX_VISIBLE_TAGS),
            verified = verified
        )
    }

    private data class CardTokenBinding(
        val position: Int,
        val targetUserId: UUID,
        val rawToken: String,
        val tokenHash: String,
        val cardRecord: SwipeCardRecord
    )

    companion object {
        val REFRESH_INTERVAL: Duration = Duration.ofHours(24)
        const val PICK_RADIUS_METERS: Int = 50_000
        const val FREE_TIER_LIMIT: Int = 1
        const val PAID_TIER_LIMIT: Int = 10
        private const val MAX_VISIBLE_PHOTOS = 5
        private const val MAX_VISIBLE_TAGS = 3
        private const val MAX_SHORT_BIO_CHARS = 120
    }
}

class TopPicksException(val code: String) : RuntimeException(code)
