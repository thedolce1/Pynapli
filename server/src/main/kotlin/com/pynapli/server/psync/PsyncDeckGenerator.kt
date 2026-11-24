package com.pynapli.server.psync

import com.pynapli.server.swipe.SwipeService
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import java.util.UUID

/**
 * Ranks candidate cards for a Psync shared deck. A narrow seam over the Swipe ranker so the
 * Psync service stays testable (tests supply a fake) and decoupled from the heavyweight
 * `SwipeService`. The generator only RANKS from the initiator's vantage with the agreed
 * filters; cross-partner safety exclusion (either partner's blocks/hidden) is applied by the
 * Psync service afterwards.
 */
interface PsyncDeckGenerator {
    suspend fun rankCandidates(
        viewerUserId: UUID,
        filters: SwipeDeckRequestDto,
        limit: Int,
    ): List<SwipeCardDto>
}

/** Production generator backed by the live Swipe deck ranker. */
class SwipeServicePsyncDeckGenerator(
    private val swipeService: SwipeService,
) : PsyncDeckGenerator {
    override suspend fun rankCandidates(
        viewerUserId: UUID,
        filters: SwipeDeckRequestDto,
        limit: Int,
    ): List<SwipeCardDto> =
        swipeService
            .queryDeckForPsync(viewerUserId, filters.copy(limit = limit, cursor = null))
            .items
}
