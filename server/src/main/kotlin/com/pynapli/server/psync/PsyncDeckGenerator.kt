package com.pynapli.server.psync

import com.pynapli.server.swipe.SwipeService
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import java.util.UUID

/**
 * Ranks candidate cards for a Psync shared deck. A narrow seam over the Swipe ranker so the
 * Psync service stays testable (tests supply a fake) and decoupled from the heavyweight
 * `SwipeService`. The generator RANKS from the initiator's vantage with the agreed filters;
 * cross-partner safety exclusion of candidates (either partner's blocks/hidden) is applied by the
 * Psync service afterwards.
 */
interface PsyncDeckGenerator {
    suspend fun rankCandidates(
        viewerUserId: UUID,
        filters: SwipeDeckRequestDto,
        limit: Int,
    ): List<SwipeCardDto>

    /**
     * The deck for the couple: ranked from [initiatorUserId]'s vantage, but a candidate's partner mini,
     * and the Linked / verified-couples filters, hold only when that partner is shown to BOTH
     * [initiatorUserId] and [partnerUserId] (PSYNC_SPEC, the strictest of the two views). The default
     * (test fakes) is [rankCandidates].
     */
    suspend fun rankCandidatesForCouple(
        initiatorUserId: UUID,
        partnerUserId: UUID,
        filters: SwipeDeckRequestDto,
        limit: Int,
    ): List<SwipeCardDto> = rankCandidates(initiatorUserId, filters, limit)
}

/** Production generator backed by the live Swipe deck ranker. */
class SwipeServicePsyncDeckGenerator(
    private val swipeService: SwipeService,
) : PsyncDeckGenerator {
    // One viewer only: the viewer's own view is applied twice, which is the same as once.
    override suspend fun rankCandidates(
        viewerUserId: UUID,
        filters: SwipeDeckRequestDto,
        limit: Int,
    ): List<SwipeCardDto> = rankCandidatesForCouple(viewerUserId, viewerUserId, filters, limit)

    override suspend fun rankCandidatesForCouple(
        initiatorUserId: UUID,
        partnerUserId: UUID,
        filters: SwipeDeckRequestDto,
        limit: Int,
    ): List<SwipeCardDto> =
        swipeService
            .queryDeckForPsync(initiatorUserId, partnerUserId, filters.copy(limit = limit, cursor = null))
            .items
}
