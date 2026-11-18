package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Swinger dealbreakers: swap / room / experience groups and boundaries survive deck relaxation. */
class SwipeDeckLifestyleRelaxationTest {
    private val filters =
        DiscoveryFilters(
            lifestyleTags = setOf("soft_swap", "same_room", "experienced", "enm"),
            boundaryItems = setOf("photos"),
        )

    @Test
    fun withoutDealbreakersEveryLifestyleGroupAndBoundaryRelaxes() {
        val relaxed = SwipeDeckRelaxation.relaxFilters(filters, dealbreakers = emptySet())
        assertEquals(emptySet(), relaxed.lifestyleTags)
        assertEquals(emptySet(), relaxed.boundaryItems)
    }

    @Test
    fun dealbreakerGroupsAreKeptAndTheRestRelax() {
        val relaxed =
            SwipeDeckRelaxation.relaxFilters(
                filters,
                dealbreakers = setOf(SwipeDealbreakerKey.SWAP, SwipeDealbreakerKey.EXPERIENCE, SwipeDealbreakerKey.BOUNDARIES),
            )
        // Room and the non-swinger "enm" tag relax; swap, experience and boundaries hold.
        assertEquals(setOf("soft_swap", "experienced"), relaxed.lifestyleTags)
        assertEquals(setOf("photos"), relaxed.boundaryItems)
    }

    @Test
    fun onlyDealbreakerLifestyleFiltersLeaveNothingToRelax() {
        val strict = DiscoveryFilters(lifestyleTags = setOf("full_swap"), boundaryItems = setOf("groups"))
        val dealbreakers =
            setOf(SwipeDealbreakerKey.SWAP, SwipeDealbreakerKey.BOUNDARIES, SwipeDealbreakerKey.DISTANCE)
        assertFalse(SwipeDeckRelaxation.hasRelaxableFilters(strict, 10_000, 100_000, dealbreakers))
        assertTrue(SwipeDeckRelaxation.hasRelaxableFilters(strict, 10_000, 100_000, setOf(SwipeDealbreakerKey.DISTANCE)))
    }
}
