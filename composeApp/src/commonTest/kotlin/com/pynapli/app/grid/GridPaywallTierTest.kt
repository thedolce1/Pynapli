package com.pynapli.app.grid

import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The free-cap row's button names the plan that lifts the cap (it always said "Social"). */
class GridPaywallTierTest {
    @Test
    fun theResultCapNamesTheNextPlanUp() {
        assertEquals(SubscriptionTier.SOCIAL, GridFeedUi(cards = emptyList(), resultLimitReached = true, resultLimitTier = "free").resultLimitUpgradeTier)
        assertEquals(SubscriptionTier.SELECT, GridFeedUi(cards = emptyList(), resultLimitReached = true, resultLimitTier = "social").resultLimitUpgradeTier)
        assertNull(GridFeedUi(cards = emptyList(), resultLimitReached = false, resultLimitTier = "free").resultLimitUpgradeTier)
    }

    @Test
    fun theButtonLabelFollowsTheTier() {
        assertEquals("Social", paywallCtaLabel(SubscriptionTier.SOCIAL))
        assertEquals("Select", paywallCtaLabel(SubscriptionTier.SELECT))
        assertEquals("See plans", paywallCtaLabel(null))
    }

    @Test
    fun aSocialViewerAtTheCapIsOfferedSelect() =
        runTest {
            val capped = GridFeedUi(cards = emptyList(), resultLimitReached = true, resultLimitTier = "social")
            val holder =
                GridStateHolder(
                    object : GridFeedRepository {
                        override suspend fun loadGrid(
                            filters: GridFilters,
                            cursor: String?,
                        ): GridFeedUi = capped
                    },
                )

            holder.load()

            assertEquals("Select unlocks the largest nearby grid.", holder.state.value.paywallMessage)
            assertEquals(SubscriptionTier.SELECT, holder.state.value.paywallUpgradeTier)
        }
}
