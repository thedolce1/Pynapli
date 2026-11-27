package com.pynapli.server.swipe

import com.pynapli.shared.network.dto.SwipeCardDto
import java.time.Instant
import java.util.UUID

internal class SwipeDeckPageBuilder(
    private val deckTokenCodec: SwipeDeckTokenCodec,
    private val dtoMapper: SwipeDtoMapper,
) {
    suspend fun buildPageItems(
        pageCards: List<SwipeCardRecord>,
        deckSession: IssuedSwipeDeckSession,
        viewerUserId: UUID,
        scopeHash: String,
        startPosition: Int,
        onlineUserIds: Set<UUID>,
        dealtAt: Instant,
    ): List<SwipeDeckPageItem> {
        val images = dtoMapper.imagesFor(pageCards)
        return pageCards.mapIndexed { index, card ->
            val position = startPosition + index
            val cardToken =
                deckTokenCodec.issueCardToken(
                    deckSessionId = deckSession.deckSessionId,
                    viewerUserId = viewerUserId,
                    targetUserId = card.userId,
                    scopeHash = scopeHash,
                    generatedAt = deckSession.generatedAt,
                    expiresAt = deckSession.expiresAt,
                    position = position,
                )
            SwipeDeckPageItem(
                dto =
                    dtoMapper.cardToDto(
                        card = card,
                        rankToken = cardToken,
                        online = card.userId in onlineUserIds,
                        onlineUserIds = onlineUserIds,
                        images = images,
                    ),
                persistedCard =
                    SwipeDeckSessionCardRecord(
                        cardId = UUID.randomUUID(),
                        sessionId = deckSession.sessionId,
                        viewerUserId = viewerUserId,
                        targetUserId = card.userId,
                        cardTokenHash = deckTokenCodec.tokenHash(cardToken),
                        position = position,
                        dealtAt = dealtAt,
                    ),
            )
        }
    }
}

internal data class SwipeDeckPageItem(
    val dto: SwipeCardDto,
    val persistedCard: SwipeDeckSessionCardRecord,
)
