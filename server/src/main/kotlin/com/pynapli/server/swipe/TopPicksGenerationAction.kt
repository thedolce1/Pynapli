package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.shared.network.dto.TopPickCardDto
import com.pynapli.shared.network.dto.TopPicksResponseDto
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class TopPicksGenerationAction(
    private val topPicksRepository: TopPicksRepository,
    private val swipeRepository: SwipeRepository,
    private val deckTokenCodec: SwipeDeckTokenCodec,
    private val cardMapper: TopPicksCardMapper,
    private val clock: Clock,
    private val refreshInterval: Duration,
    private val pickRadiusMeters: Int,
    private val sessionIdFactory: () -> UUID,
    private val cardIdFactory: () -> UUID,
) {
    suspend fun generateFreshSession(
        viewerUserId: UUID,
        now: Instant = clock.instant(),
        tierLimit: Int,
    ): TopPicksResponseDto {
        // Quality bias per spec L311: verified profiles are ordered first via the
        // existing soft_score ORDER BY. Already-actioned targets are excluded by
        // the swipe SQL itself so Picks never re-show profiles the user passed.
        val filters = DiscoveryFilters(verifiedPreferred = true)
        val deckResult =
            swipeRepository.queryDeck(
                viewerUserId = viewerUserId,
                radiusMeters = pickRadiusMeters,
                limit = tierLimit,
                filters = filters,
            )
        if (!deckResult.viewerHadLocation) throw TopPicksException("LOCATION_REQUIRED")

        val candidates = deckResult.cards.take(tierLimit)
        val scopeHash =
            SwipeDeckTokenCodec.deckScopeHash(
                viewerUserId = viewerUserId,
                radiusMeters = pickRadiusMeters,
                limit = tierLimit,
                filters = filters,
            )
        val deckSession =
            deckTokenCodec.issueDeckSession(
                viewerUserId = viewerUserId,
                scopeHash = scopeHash,
                now = now,
                ttl = refreshInterval,
            )
        val expiresAt = now.plus(refreshInterval)
        val tokens = candidates.issueCardTokens(deckSession, viewerUserId, scopeHash, now)
        val saved = persistBackingSessions(viewerUserId, deckSession, scopeHash, filters, now, expiresAt, tierLimit, tokens)

        val onlineIds = cardMapper.onlineFor(candidates.map { it.userId }.toSet())
        return TopPicksResponseDto(
            sessionId = saved.session.sessionId.toString(),
            deckSessionId = deckSession.deckSessionId,
            generatedAt = saved.session.generatedAt.toString(),
            expiresAt = saved.session.expiresAt.toString(),
            tierLimit = tierLimit,
            picks =
                tokens.map { binding ->
                    TopPickCardDto(
                        position = binding.position,
                        card = cardMapper.toCardDto(binding.cardRecord, binding.rawToken, onlineIds),
                    )
                },
        )
    }

    private fun List<SwipeCardRecord>.issueCardTokens(
        deckSession: IssuedSwipeDeckSession,
        viewerUserId: UUID,
        scopeHash: String,
        now: Instant,
    ): List<TopPickCardTokenBinding> =
        mapIndexed { index, card ->
            val raw =
                deckTokenCodec.issueCardToken(
                    deckSessionId = deckSession.deckSessionId,
                    viewerUserId = viewerUserId,
                    targetUserId = card.userId,
                    scopeHash = scopeHash,
                    generatedAt = deckSession.generatedAt,
                    expiresAt = deckSession.expiresAt,
                    position = index,
                )
            TopPickCardTokenBinding(
                position = index,
                targetUserId = card.userId,
                rawToken = raw,
                tokenHash = deckTokenCodec.tokenHash(raw),
                cardRecord = card,
                dealtAt = now,
            )
        }

    private suspend fun persistBackingSessions(
        viewerUserId: UUID,
        deckSession: IssuedSwipeDeckSession,
        scopeHash: String,
        filters: DiscoveryFilters,
        now: Instant,
        expiresAt: Instant,
        tierLimit: Int,
        tokens: List<TopPickCardTokenBinding>,
    ): TopPickSessionWithCards {
        swipeRepository.upsertDeckSession(
            session =
                SwipeDeckSessionRecord(
                    sessionId = deckSession.sessionId,
                    deckSessionToken = deckSession.deckSessionId,
                    deckSessionTokenHash = deckSession.deckSessionTokenHash,
                    viewerUserId = viewerUserId,
                    scopeHash = scopeHash,
                    radiusMeters = pickRadiusMeters,
                    pageLimit = tierLimit,
                    filterPayload = filters.toPersistedJson(),
                    generatedAt = deckSession.generatedAt,
                    expiresAt = deckSession.expiresAt,
                ),
            cards =
                tokens.map { binding ->
                    SwipeDeckSessionCardRecord(
                        cardId = UUID.randomUUID(),
                        sessionId = deckSession.sessionId,
                        viewerUserId = viewerUserId,
                        targetUserId = binding.targetUserId,
                        cardTokenHash = binding.tokenHash,
                        position = binding.position,
                        dealtAt = now,
                    )
                },
        )
        return topPicksRepository.createSession(
            sessionId = sessionIdFactory(),
            userId = viewerUserId,
            deckSessionId = deckSession.sessionId,
            generatedAt = now,
            expiresAt = expiresAt,
            cards =
                tokens.map { binding ->
                    TopPickCardInsert(
                        cardId = cardIdFactory(),
                        targetUserId = binding.targetUserId,
                        position = binding.position,
                        cardTokenHash = binding.tokenHash,
                        dealtAt = now,
                    )
                },
        )
    }
}
