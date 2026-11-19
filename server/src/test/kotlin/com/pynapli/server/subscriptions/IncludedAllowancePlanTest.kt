package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class IncludedAllowancePlanTest {
    private val anchor = Instant.parse("2026-11-02T10:00:00.123456Z")

    @Test
    fun periodsAreWindowsLaidEndToEndFromTheAnchor() {
        val week = IncludedAllowancePlan.WEEK

        assertEquals(
            IncludedAllowancePeriod(anchor, anchor.plus(week)),
            IncludedAllowancePlan.periodContaining(anchor, week, anchor),
        )
        assertEquals(
            IncludedAllowancePeriod(anchor, anchor.plus(week)),
            IncludedAllowancePlan.periodContaining(anchor, week, anchor.plus(week).minusNanos(1_000)),
        )
        assertEquals(
            IncludedAllowancePeriod(anchor.plus(week), anchor.plus(week.multipliedBy(2))),
            IncludedAllowancePlan.periodContaining(anchor, week, anchor.plus(week)),
        )
        assertEquals(
            IncludedAllowancePeriod(anchor.plus(Duration.ofDays(30)), anchor.plus(Duration.ofDays(60))),
            IncludedAllowancePlan.periodContaining(anchor, IncludedAllowancePlan.MONTH, anchor.plus(Duration.ofDays(45))),
        )
    }

    @Test
    fun everyPaidSourceGetsItsTiersAllowance() {
        listOf(
            EntitlementSource.OWN_SUBSCRIPTION,
            EntitlementSource.LINKED_PARTNER_BENEFIT,
            EntitlementSource.PROMO,
            EntitlementSource.ADMIN,
        ).forEach { source ->
            val entitlement =
                SubscriptionEntitlementDto(
                    tier = SubscriptionTier.SELECT,
                    source = source,
                    displayName = "Pynapli Select",
                    linkedPartnerIncluded = true,
                    startsAt = anchor.toString(),
                )
            val pineapple = IncludedAllowancePlan.forEntitlement(entitlement, PremiumInventoryType.PINEAPPLE_LIKE)
            val boost = IncludedAllowancePlan.forEntitlement(entitlement, PremiumInventoryType.BOOST)

            assertEquals(5, pineapple.quantity, source)
            assertEquals(IncludedAllowancePlan.WEEK, pineapple.period)
            assertEquals(anchor, pineapple.entitlementStartedAt)
            assertEquals(1, boost.quantity, source)
            assertEquals(IncludedAllowancePlan.MONTH, boost.period)
        }
    }
}
