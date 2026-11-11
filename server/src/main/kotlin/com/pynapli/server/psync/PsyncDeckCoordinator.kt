package com.pynapli.server.psync

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.swipe.SwipeDeckTokenCodec
import com.pynapli.shared.network.dto.PsyncDeckCardDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal class PsyncDeckCoordinator(
    private val repository: PsyncSessionRepository,
    private val deckGenerator: PsyncDeckGenerator?,
    private val blockRepository: BlockRepository?,
    private val hiddenProfileRepository: HiddenProfileRepository?,
    private val presenceService: PresenceService?,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    private val deckTokenCodec: SwipeDeckTokenCodec,
    private val clock: Clock,
) {
    suspend fun activateWithGeneratedDeck(
        sessionId: UUID,
        session: PsyncSession,
    ): PsyncSessionRepository.MutateOutcome {
        val partnerId = session.partnerIdForInitiator() ?: throw PsyncException("PSYNC_INVALID_STATE")
        val generator = deckGenerator ?: throw PsyncException("PSYNC_DECK_EMPTY")

        val oversample = (session.deckSize * 2).coerceAtMost(PSYNC_MAX_RANK_LIMIT)
        val ranked = generator.rankCandidates(session.initiatorUserId, session.filters, oversample)

        val rankedIds = ranked.mapNotNull { runCatching { UUID.fromString(it.userId) }.getOrNull() }
        val excluded =
            buildSet {
                add(session.initiatorUserId)
                add(partnerId)
                addAll(blockedOrHiddenTargets(session.initiatorUserId, partnerId))
                // The ranker ran from the initiator's side only. Drop anyone EITHER partner has
                // already swiped on, matched with or reported, so nobody is asked twice.
                addAll(repository.alreadySettledTargets(listOf(session.initiatorUserId, partnerId), rankedIds))
            }
        val deckCards =
            ranked
                .asSequence()
                .filter { runCatching { UUID.fromString(it.userId) }.getOrNull()?.let { id -> id !in excluded } ?: false }
                .distinctBy { it.userId }
                .take(session.deckSize)
                .toList()
        if (deckCards.isEmpty()) throw PsyncException("PSYNC_DECK_EMPTY")

        val now = clock.instant()
        val deckSessionId = UUID.randomUUID()
        val cards =
            deckCards.mapIndexed { position, card ->
                val targetId = UUID.fromString(card.userId)
                val rawToken = issueCardToken(session, deckSessionId, targetId, position, now)
                PsyncSessionCard(
                    id = UUID.randomUUID(),
                    sessionId = sessionId,
                    position = position,
                    targetUserId = targetId,
                    cardTokenHash = deckTokenCodec.tokenHash(rawToken),
                    snapshot = card,
                    dealtAt = now,
                )
            }

        return repository.activateWithDeck(sessionId, deckSessionId, cards, now)
    }

    /** The shared ordered deck for a participant; each card carries the viewer's OWN action only. */
    suspend fun buildDeckResponse(
        session: PsyncSession,
        viewerUserId: UUID,
    ): PsyncDeckResponseDto {
        val partnerId = session.partnerIdForInitiator()
        val invalidated =
            if (partnerId == null) {
                emptySet()
            } else {
                blockedOrHiddenTargets(session.initiatorUserId, partnerId)
            }
        val cards = repository.listDeckCards(session.id).filterNot { it.targetUserId in invalidated }
        val myActions = repository.listActionsForActor(session.id, viewerUserId).associate { it.targetUserId to it.action }
        val online = onlineFor(cards.map { it.targetUserId })
        val items =
            cards.map { card ->
                val rawToken =
                    issueCardToken(
                        session = session,
                        deckSessionId = session.deckSessionId!!,
                        targetUserId = card.targetUserId,
                        position = card.position,
                        generatedAt = session.startedAt!!,
                    )
                PsyncDeckCardDto(
                    // The snapshot's signed photo links expire within minutes; issue fresh ones.
                    card = card.snapshot.copy(online = card.targetUserId in online).withFreshPhotoUrls(mediaAssetUrlResolver),
                    cardToken = rawToken,
                    viewerAction = myActions[card.targetUserId],
                )
            }
        return PsyncDeckResponseDto(sessionId = session.id.toString(), deckSize = session.deckSize, cards = items)
    }

    /** Targets blocked (either direction) or hidden by EITHER partner — invalid for the deck. */
    suspend fun blockedOrHiddenTargets(
        partnerA: UUID,
        partnerB: UUID,
    ): Set<UUID> =
        buildSet {
            blockRepository?.let {
                addAll(it.listBlockedRelatedUserIds(partnerA))
                addAll(it.listBlockedRelatedUserIds(partnerB))
            }
            hiddenProfileRepository?.let {
                addAll(it.listHiddenProfileIds(partnerA))
                addAll(it.listHiddenProfileIds(partnerB))
            }
        }

    fun issueCardToken(
        session: PsyncSession,
        deckSessionId: UUID,
        targetUserId: UUID,
        position: Int,
        generatedAt: Instant,
    ): String =
        deckTokenCodec.issueCardToken(
            deckSessionId = deckSessionId.toString(),
            viewerUserId = session.initiatorUserId,
            targetUserId = targetUserId,
            scopeHash = "psync:${session.id}",
            generatedAt = generatedAt,
            expiresAt = session.expiresAt,
            position = position,
        )

    private suspend fun onlineFor(userIds: List<UUID>): Set<UUID> = runCatching { presenceService?.onlineUserIds(userIds).orEmpty() }.getOrDefault(emptySet())
}
