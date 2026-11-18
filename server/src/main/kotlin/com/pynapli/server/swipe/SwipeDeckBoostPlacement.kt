package com.pynapli.server.swipe

/**
 * Where boosted people go in a Swipe deck page (MONETIZATION_SPEC "Boost", SWIPE_IMPLEMENTATION_SPEC
 * "Boost / Super Boost"). The rank query brings every eligible boosted candidate inside the radius
 * step into the candidate set, however far down the ordinary order they sit; this lays out the page:
 *
 *  - While any unboosted card is left, boosted cards go only in the boost slots: the first card of
 *    every [SLOT_EVERY] (positions 0, 4, 8, ...), in their natural order. So a page holds at most
 *    one boosted card in every four, whether the Boost lifted them or they were near anyway.
 *  - The one exception: once no unboosted card is left to show, the remaining boosted ones follow
 *    in order rather than leaving the deck short.
 *
 * Input rows must be in natural order (soft score, coarse distance bucket, tiebreak) and already
 * stripped of the viewer's exclusions (swiped, dealt earlier in this session, blocked, self).
 */
internal object SwipeDeckBoostPlacement {
    const val SLOT_EVERY: Int = 4

    fun arrange(
        rows: List<DeckRankRow>,
        limit: Int,
    ): List<DeckRankRow> {
        if (limit <= 0 || rows.isEmpty()) return emptyList()
        val boosted = ArrayDeque(rows.filter { it.isBoosted })
        val regular = ArrayDeque(rows.filterNot { it.isBoosted })
        val page = ArrayList<DeckRankRow>(limit)
        while (page.size < limit && (boosted.isNotEmpty() || regular.isNotEmpty())) {
            val boostSlot = page.size % SLOT_EVERY == 0
            page +=
                when {
                    regular.isEmpty() -> boosted.removeFirst()
                    boostSlot && boosted.isNotEmpty() -> boosted.removeFirst()
                    else -> regular.removeFirst()
                }
        }
        return page
    }
}
