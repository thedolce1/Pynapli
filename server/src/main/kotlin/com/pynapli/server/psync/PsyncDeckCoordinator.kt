package com.pynapli.server.psync

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.discovery.PartnerFilterSql
import com.pynapli.server.swipe.SwipeDeckRequestParser
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
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    suspend fun activateWithGeneratedDeck(
        sessionId: UUID,
        session: PsyncSession,
    ): PsyncSessionRepository.MutateOutcome {
        val partnerId = session.partnerIdForInitiator() ?: throw PsyncException("PSYNC_INVALID_STATE")
        val generator = deckGenerator ?: throw PsyncException("PSYNC_DECK_EMPTY")

        val oversample = (session.deckSize * 2).coerceAtMost(PSYNC_MAX_RANK_LIMIT)
        // Partner minis and the Linked / verified-couples filters hold for BOTH partners (PSYNC_SPEC).
        val ranked = generator.rankCandidatesForCouple(session.initiatorUserId, partnerId, session.filters, oversample)

        val rankedIds = ranked.mapNotNull { runCatching { UUID.fromString(it.userId) }.getOrNull() }
        // Defense in depth for the partner filters and "No single men" (plan G2-3): the couple rank
        // above already applies them for both partners; this re-check makes the deck hold that rule
        // even if a generator ranked from one view only.
        val partnerFilterFails = partnerFilterFailures(session, partnerId, rankedIds)
        val excluded =
            buildSet {
                add(session.initiatorUserId)
                add(partnerId)
                addAll(blockedOrHiddenTargets(session.initiatorUserId, partnerId))
                // The ranker ran from the initiator's side only. Drop anyone EITHER partner has
                // already swiped on, matched with or reported, so nobody is asked twice.
                addAll(repository.alreadySettledTargets(listOf(session.initiatorUserId, partnerId), rankedIds))
                addAll(partnerFilterFails)
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

    /** Ranked ids failing the partner-derived filters from either partner's view (plan G2-3). */
    private suspend fun partnerFilterFailures(
        session: PsyncSession,
        partnerId: UUID,
        rankedIds: List<UUID>,
    ): Set<UUID> {
        if (rankedIds.isEmpty()) return emptySet()
        val filters =
            runCatching {
                SwipeDeckRequestParser
                    .parse(session.filters, minRadiusMeters = 0, maxRadiusMeters = Int.MAX_VALUE, maxLimit = Int.MAX_VALUE)
                    .filters
            }.getOrElse { throw PsyncException("INVALID_PSYNC_FILTER") }
        if (!PartnerFilterSql.appliesTo(filters)) return emptySet()
        // Both partners as viewers, and the union of the people hiding from either: the same predicate
        // and binding the Psync rank uses (SwipeDeckQueryAction partnerHiddenIds, coViewerUserId), so
        // a card passes only when its partner is shown to BOTH and satisfies the filter.
        val viewers = listOf(session.initiatorUserId, partnerId)
        val hiddenFromEither =
            contactBlockVisibility
                ?.let { it.hiddenFrom(session.initiatorUserId) + it.hiddenFrom(partnerId) }
                .orEmpty()
        val passing = repository.partnerFilterMatches(viewers, rankedIds, filters, hiddenFromEither)
        return rankedIds.filterNot { it in passing }.toSet()
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
        // "Hide from people you know", per person: a card of someone hiding from THIS viewer is left out
        // of this viewer's view of the shared deck only (their partner still sees and answers it, so the
        // hidden person's world is the same whether or not the address was theirs), and a partner mini
        // of such a person comes off the card (with the couple badge).
        val hiddenFromViewer = contactBlockVisibility?.hiddenFrom(viewerUserId).orEmpty()
        val allCards = repository.listDeckCards(session.id).filterNot { it.targetUserId in invalidated }
        val cards = allCards.filterNot { it.targetUserId in hiddenFromViewer }
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
                    card =
                        card.snapshot
                            .copy(online = card.targetUserId in online)
                            .let { snapshot ->
                                if (snapshot.linkedPartner?.userId?.let { runCatching { UUID.fromString(it) }.getOrNull() } in hiddenFromViewer) {
                                    snapshot.copy(linkedPartner = null, coupleVerified = false)
                                } else {
                                    snapshot
                                }
                            }.withFreshPhotoUrls(mediaAssetUrlResolver),
                    cardToken = rawToken,
                    viewerAction = myActions[card.targetUserId],
                )
            }
        // A hidden card this viewer already answered (before the block bound) still counts toward their
        // deck, so their count and their acted count stay consistent; only unanswered ones come off.
        val hiddenUnanswered = allCards.count { it.targetUserId in hiddenFromViewer && it.targetUserId !in myActions }
        val deckSize = (session.deckSize - hiddenUnanswered).coerceAtLeast(0)
        return PsyncDeckResponseDto(sessionId = session.id.toString(), deckSize = deckSize, cards = items)
    }

    /**
     * "Hide from people you know": [targetUserId] hides from [userId], so [userId] can't act on that
     * card (the deck response already leaves it out for them; their partner still can).
     */
    suspend fun noteActToward(
        actorUserId: UUID,
        targetUserId: UUID,
    ) {
        contactBlockVisibility?.noteActToward(actorUserId, targetUserId)
    }

    suspend fun targetHidesFrom(
        userId: UUID,
        targetUserId: UUID,
    ): Boolean = contactBlockVisibility?.isHiddenFrom(userId, targetUserId) == true

    /**
     * How many cards of the session's deck [userId] never gets and never answered (people hiding from
     * them). A card they answered before the block bound stays counted, since their acted count holds it.
     */
    suspend fun hiddenCardCount(
        sessionId: UUID,
        userId: UUID,
    ): Int {
        val hidden = contactBlockVisibility?.hiddenFrom(userId).orEmpty()
        if (hidden.isEmpty()) return 0
        val answered = repository.listActionsForActor(sessionId, userId).mapTo(mutableSetOf()) { it.targetUserId }
        return repository.listDeckCards(sessionId).count { it.targetUserId in hidden && it.targetUserId !in answered }
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
            // "Hide from people you know" is applied per person, not here (see buildDeckResponse).
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
