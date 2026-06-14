package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.shared.network.dto.TopPickCardDto
import com.pynapli.shared.network.dto.TopPicksResponseDto
import java.util.UUID

internal class TopPicksCachedResponseAction(
    private val swipeRepository: SwipeRepository,
    private val deckTokenCodec: SwipeDeckTokenCodec,
    private val cardMapper: TopPicksCardMapper,
    private val pickRadiusMeters: Int,
) {
    suspend fun toResponse(
        sessionWithCards: TopPickSessionWithCards,
        viewerUserId: UUID,
        tierLimit: Int,
    ): TopPicksResponseDto {
        if (sessionWithCards.cards.isEmpty()) {
            return TopPicksResponseDto(
                sessionId = sessionWithCards.session.sessionId.toString(),
                deckSessionId = "",
                generatedAt = sessionWithCards.session.generatedAt.toString(),
                expiresAt = sessionWithCards.session.expiresAt.toString(),
                picks = emptyList(),
                tierLimit = tierLimit,
            )
        }

        val targetIds = sessionWithCards.cards.map { it.targetUserId }.toSet()
        val byTarget = hydrateCachedTargets(viewerUserId, targetIds)
        val onlineIds = cardMapper.onlineFor(targetIds)
        val backingSession =
            swipeRepository.findDeckSession(sessionWithCards.session.deckSessionId, viewerUserId)
                ?: throw TopPicksException("STALE_TOP_PICK_SESSION")

        return TopPicksResponseDto(
            sessionId = sessionWithCards.session.sessionId.toString(),
            deckSessionId = backingSession.deckSessionToken,
            generatedAt = sessionWithCards.session.generatedAt.toString(),
            expiresAt = sessionWithCards.session.expiresAt.toString(),
            tierLimit = tierLimit,
            picks =
                sessionWithCards.cards.mapNotNull { record ->
                    val candidate = byTarget[record.targetUserId] ?: return@mapNotNull null
                    val rawToken =
                        deckTokenCodec.issueCardToken(
                            deckSessionId = backingSession.deckSessionToken,
                            viewerUserId = viewerUserId,
                            targetUserId = record.targetUserId,
                            scopeHash = backingSession.scopeHash,
                            generatedAt = backingSession.generatedAt,
                            expiresAt = backingSession.expiresAt,
                            position = record.position,
                        )
                    if (deckTokenCodec.tokenHash(rawToken) != record.cardTokenHash) {
                        return@mapNotNull null
                    }
                    TopPickCardDto(
                        position = record.position,
                        card = cardMapper.toCardDto(candidate, rawToken, onlineIds),
                    )
                },
        )
    }

    private suspend fun hydrateCachedTargets(
        viewerUserId: UUID,
        targetIds: Set<UUID>,
    ): Map<UUID, SwipeCardRecord> {
        val deckResult =
            swipeRepository.queryDeck(
                viewerUserId = viewerUserId,
                radiusMeters = pickRadiusMeters,
                limit = (targetIds.size * 4).coerceAtLeast(targetIds.size),
                filters = DiscoveryFilters(verifiedPreferred = true),
            )
        return deckResult.cards.associateBy { it.userId }
    }
}
