package com.pynapli.app.swipe

import com.pynapli.app.grid.SinglesShortcut
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan G2-4: Swipe's filter sheet has the Grid's Partner section and Who shortcuts. */
class SwipePartnerFiltersTest {
    @Test
    fun partnerFiltersAndNoSingleMenAreSentCountedAndRoundTrip() {
        val filters =
            SwipeFilters(
                linked = true,
                partnerGenders = setOf("woman"),
                partnerMinimumAge = 30,
                partnerMaximumAge = 45,
                partnerOrientations = setOf("bisexual"),
                partnerVerified = true,
                noSingleMen = true,
            )
        val request = filters.toSwipeDeckRequest()

        assertEquals(listOf("woman"), request.partnerGenders)
        assertEquals(30, request.partnerMinimumAge)
        assertEquals(45, request.partnerMaximumAge)
        assertEquals(listOf("bisexual"), request.partnerOrientations)
        assertTrue(request.partnerVerifiedOnly)
        assertTrue(request.excludeSingleMen)
        // Linked + 4 partner filters + No single men.
        assertEquals(6, filters.activeCount)
        assertEquals(filters, SwipeFilters.fromSwipeDeckRequest(request))
        assertEquals(SwipeFilters.defaults, SwipeFilters.fromSwipeDeckRequest(SwipeFilters.defaults.toSwipeDeckRequest()))
    }

    @Test
    fun singlesAloneRulesThePartnerFiltersOutAndNeverSendsThem() {
        val filters = SwipeFilters(singles = true, partnerVerified = true, partnerGenders = setOf("man"))
        assertFalse(filters.partnerFiltersApply)
        val request = filters.toSwipeDeckRequest()
        assertTrue(request.partnerGenders.isEmpty())
        assertFalse(request.partnerVerifiedOnly)
        assertEquals(1, filters.activeCount)
    }

    @Test
    fun theGridShortcutsWorkOnTheSwipeDraft() {
        val draft =
            SwipeFilters(
                partnered = true,
                partnerVerified = true,
                dealbreakers = setOf(SwipeDealbreakerKey.LINKED_ONLY),
            )
        val singleWomen = draft.withGridWhoAndPartner(SinglesShortcut.SingleWomen.applied(draft.asGridWhoAndPartner()))

        assertTrue(singleWomen.singles)
        assertFalse(singleWomen.linked)
        assertFalse(singleWomen.partnered)
        assertTrue(SinglesShortcut.SingleWomen.isActive(singleWomen.asGridWhoAndPartner()))
        assertFalse(singleWomen.partnerVerified, "Singles rules the partner filters out")
        assertFalse(SwipeDealbreakerKey.LINKED_ONLY in singleWomen.dealbreakers, "no Linked lock without Linked")

        // An unrelated Partner change keeps Swipe's own Linked / Partnered choice as it was.
        val partneredDraft = SwipeFilters(partnered = true)
        val withAge = partneredDraft.withGridWhoAndPartner(partneredDraft.asGridWhoAndPartner().copy(partnerMinimumAge = 25))
        assertTrue(withAge.partnered)
        assertFalse(withAge.linked)
        assertEquals(25, withAge.partnerMinimumAge)
    }
}
