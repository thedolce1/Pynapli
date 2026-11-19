package com.pynapli.server.subscriptions

import com.pynapli.server.swipe.SwipeInventorySpend
import com.pynapli.server.swipe.recordSwipeActionTransaction
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.raceTogether
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan's included allowances (MONETIZATION_SPEC "Included allowances"): Social 1 Pineapple Like a
 * week, Select 5 Pineapple Likes a week and 1 Boost a month, granted lazily and exactly once per
 * period, spent before purchased units, never stacked, and never more than one period's worth per
 * person per period whatever happens to the plan or the partner link.
 */
class IncludedAllowanceIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val start = Instant.parse("2026-11-02T10:00:00Z")
    private val clock = SteppingClock(start)
    private val repository = JdbcSubscriptionRepository(dataSource)

    // No entitlement cache: these tests change the plan and read it back at once.
    private val service =
        SubscriptionService(repository = repository, clock = clock, entitlementCacheTtl = Duration.ZERO)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    // ---- Per tier -------------------------------------------------------------------------------

    @Test
    fun aNewSelectSubscriberHasFivePineappleLikesAndOneBoostAtOnce() =
        runBlocking<Unit> {
            val member = user()
            subscribe(member, SubscriptionTier.SELECT)

            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(1, balance(member, PremiumInventoryType.BOOST))
        }

    @Test
    fun aNewSocialSubscriberHasOnePineappleLikeAndNoBoost() =
        runBlocking<Unit> {
            val member = user()
            subscribe(member, SubscriptionTier.SOCIAL)

            assertEquals(1, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(0, balance(member, PremiumInventoryType.BOOST))
        }

    @Test
    fun freeIsGrantedNothingAndLeavesNoRows() =
        runBlocking<Unit> {
            val member = user()

            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(0, balance(member, PremiumInventoryType.BOOST))
            assertNull(service.consumePineappleLikeInventoryReturningId(member))
            assertEquals(0, includedRowCount(member))
            assertFalse(hasAnchor(member))
        }

    @Test
    fun aPromoEntitlementIncludesTheSameAllowanceAsItsTier() =
        runBlocking<Unit> {
            val member = user()
            repository.grantStoredEntitlement(
                userId = member,
                tier = SubscriptionTier.SELECT,
                source = EntitlementSource.PROMO,
                startsAt = clock.instant(),
                endsAt = clock.instant().plus(Duration.ofDays(60)),
                actorId = "billing-ops",
                reason = "launch promo",
            )

            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(1, balance(member, PremiumInventoryType.BOOST))
        }

    // ---- Included before purchased, periods -----------------------------------------------------

    @Test
    fun includedPineappleLikesAreSpentBeforePurchasedOnes() =
        runBlocking<Unit> {
            val member = user()
            subscribe(member, SubscriptionTier.SELECT)
            buy(member, PremiumInventoryType.PINEAPPLE_LIKE, 5)

            assertEquals(10, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            repeat(5) { assertNotNull(service.consumePineappleLikeInventoryReturningId(member)) }

            assertEquals(5, purchasedLeft(member, PremiumInventoryType.PINEAPPLE_LIKE), "purchased untouched while included last")
            assertEquals(0, includedLeft(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertNotNull(service.consumePineappleLikeInventoryReturningId(member))
            assertEquals(4, purchasedLeft(member, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    @Test
    fun anUnusedAllowanceDoesNotStackIntoTheNextWeek() =
        runBlocking<Unit> {
            val member = user()
            subscribe(member, SubscriptionTier.SELECT)
            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))

            clock.advance(Duration.ofDays(7))

            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            clock.advance(Duration.ofDays(14))
            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    @Test
    fun theAllowanceRefreshesWhenTheWeekRollsAndNotBefore() =
        runBlocking<Unit> {
            val member = user()
            subscribe(member, SubscriptionTier.SELECT)
            repeat(5) { assertNotNull(service.consumePineappleLikeInventoryReturningId(member)) }
            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))

            clock.advance(Duration.ofDays(7).minusSeconds(1))
            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertNull(service.consumePineappleLikeInventoryReturningId(member))

            clock.advance(Duration.ofSeconds(1))
            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    @Test
    fun theWeekIsCountedFromWhenThePaidEntitlementBegan() =
        runBlocking<Unit> {
            val member = user()
            val began = clock.instant().minus(Duration.ofDays(3))
            subscribe(member, SubscriptionTier.SELECT, startsAt = began)

            val inventory = service.getInventory(member)
            val pineapple = inventory.balances.first { it.inventoryType == PremiumInventoryType.PINEAPPLE_LIKE }

            assertEquals(5, pineapple.quantity)
            assertEquals(began.plus(Duration.ofDays(7)).toString(), pineapple.nextExpiresAt?.let { Instant.parse(it).toString() })
        }

    @Test
    fun purchasedUnitsSurviveRolloversAndALapse() =
        runBlocking<Unit> {
            val member = user()
            val subscription = subscribe(member, SubscriptionTier.SELECT)
            buy(member, PremiumInventoryType.PINEAPPLE_LIKE, 3)
            buy(member, PremiumInventoryType.BOOST, 2)
            assertEquals(8, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))

            clock.advance(Duration.ofDays(35))
            assertEquals(8, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(3, balance(member, PremiumInventoryType.BOOST))

            endSubscription(subscription)
            assertEquals(3, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(2, balance(member, PremiumInventoryType.BOOST))
            assertEquals(3, purchasedLeft(member, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    // ---- Tier changes ---------------------------------------------------------------------------

    @Test
    fun anUpgradeTopsUpToTheHigherAllowanceForThisWeek() =
        runBlocking<Unit> {
            val member = user()
            val social = subscribe(member, SubscriptionTier.SOCIAL)
            assertNotNull(service.consumePineappleLikeInventoryReturningId(member))
            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))

            endSubscription(social)
            subscribe(member, SubscriptionTier.SELECT)

            // Select's 5 for the week, less the one already used this week.
            assertEquals(4, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(1, balance(member, PremiumInventoryType.BOOST))
        }

    @Test
    fun aDowngradeCapsThisWeeksUnusedUnitsAtTheLowerAllowance() =
        runBlocking<Unit> {
            val member = user()
            val select = subscribe(member, SubscriptionTier.SELECT)
            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))

            endSubscription(select)
            val social = subscribe(member, SubscriptionTier.SOCIAL)
            assertEquals(1, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(0, balance(member, PremiumInventoryType.BOOST))

            // Back to Select: the withheld units come back, never more than 5 in the week.
            endSubscription(social)
            subscribe(member, SubscriptionTier.SELECT)
            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    @Test
    fun aDowngradeAfterUsingMoreThanTheLowerTierIncludesLeavesNone() =
        runBlocking<Unit> {
            val member = user()
            val select = subscribe(member, SubscriptionTier.SELECT)
            repeat(2) { assertNotNull(service.consumePineappleLikeInventoryReturningId(member)) }

            endSubscription(select)
            subscribe(member, SubscriptionTier.SOCIAL)

            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertNull(service.consumePineappleLikeInventoryReturningId(member))
        }

    @Test
    fun aLapseMakesThisWeeksUnusedIncludedUnitsUnusable() =
        runBlocking<Unit> {
            val member = user()
            val select = subscribe(member, SubscriptionTier.SELECT)
            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))

            endSubscription(select)

            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(0, balance(member, PremiumInventoryType.BOOST))
            assertNull(service.consumePineappleLikeInventoryReturningId(member))
            assertFailsWith<SubscriptionException> { service.activateBoost(member) }
        }

    @Test
    fun aRefundAfterALapseDoesNotMakeAnIncludedUnitUsable() =
        runBlocking<Unit> {
            val member = user()
            val select = subscribe(member, SubscriptionTier.SELECT)
            val spent = assertNotNull(service.consumePineappleLikeInventoryReturningId(member))
            endSubscription(select)
            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))

            // A Psync session that never delivered gives the unit back after the plan lapsed.
            assertTrue(service.refundPineappleLikeInventory(spent))

            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertNull(service.consumePineappleLikeInventoryReturningId(member))
            // And it is there again, with the other unused four, if Select comes back this week.
            subscribe(member, SubscriptionTier.SELECT)
            assertEquals(5, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    @Test
    fun subscribingAndCancellingRepeatedlyInOneWeekGrantsOnce() =
        runBlocking<Unit> {
            val member = user()
            var subscription = subscribe(member, SubscriptionTier.SELECT)
            repeat(5) { assertNotNull(service.consumePineappleLikeInventoryReturningId(member)) }

            repeat(3) {
                endSubscription(subscription)
                assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
                clock.advance(Duration.ofHours(1))
                // A fresh purchase starts a new billing period now; the allowance week does not move.
                subscription = subscribe(member, SubscriptionTier.SELECT)
                assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
            }
            assertEquals(1, includedRowCount(member, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    // ---- Linked partner -------------------------------------------------------------------------

    @Test
    fun aCoveredPartnerHasTheirOwnAllowanceAndLosesUnusedUnitsOnUnlink() =
        runBlocking<Unit> {
            val subscriber = user()
            val partner = user()
            subscribe(subscriber, SubscriptionTier.SELECT)
            link(beneficiary = partner, subscriber = subscriber)

            assertEquals(5, balance(partner, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(1, balance(partner, PremiumInventoryType.BOOST))
            repeat(2) { assertNotNull(service.consumePineappleLikeInventoryReturningId(partner)) }
            // Not shared: the subscriber's own allowance is untouched by the partner's spending.
            assertEquals(5, balance(subscriber, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(3, balance(partner, PremiumInventoryType.PINEAPPLE_LIKE))

            unlink(beneficiary = partner, subscriber = subscriber)

            assertEquals(0, balance(partner, PremiumInventoryType.PINEAPPLE_LIKE))
            assertNull(service.consumePineappleLikeInventoryReturningId(partner))
            assertEquals(5, balance(subscriber, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    @Test
    fun linkingAndUnlinkingRepeatedlyInOneWeekGrantsOnce() =
        runBlocking<Unit> {
            val subscriber = user()
            val partner = user()
            subscribe(subscriber, SubscriptionTier.SELECT)
            link(beneficiary = partner, subscriber = subscriber)
            repeat(5) { assertNotNull(service.consumePineappleLikeInventoryReturningId(partner)) }

            repeat(3) {
                unlink(beneficiary = partner, subscriber = subscriber)
                assertEquals(0, balance(partner, PremiumInventoryType.PINEAPPLE_LIKE))
                relink(beneficiary = partner, subscriber = subscriber)
                assertEquals(0, balance(partner, PremiumInventoryType.PINEAPPLE_LIKE))
            }
            assertNull(service.consumePineappleLikeInventoryReturningId(partner))
            assertEquals(1, includedRowCount(partner, PremiumInventoryType.PINEAPPLE_LIKE))

            clock.advance(Duration.ofDays(7))
            assertEquals(5, balance(partner, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    // ---- Exactly once under concurrency ---------------------------------------------------------

    @Test
    fun concurrentRequestsAtThePeriodStartGrantExactlyOnce() =
        runBlocking<Unit> {
            var wrong = 0
            repeat(ITERATIONS) {
                val member = user()
                subscribe(member, SubscriptionTier.SELECT)
                coroutineScope {
                    val gate = CompletableDeferred<Unit>()
                    val requests =
                        (1..CONCURRENT_REQUESTS).map { index ->
                            async(Dispatchers.IO) {
                                gate.await()
                                runCatching {
                                    if (index % 2 == 0) {
                                        service.getInventory(member)
                                    } else {
                                        service.consumePineappleLikeInventoryReturningId(member)
                                    }
                                }
                            }
                        }
                    gate.complete(Unit)
                    requests.awaitAll().forEach { it.getOrThrow() }
                }
                val spends = CONCURRENT_REQUESTS / 2 + CONCURRENT_REQUESTS % 2
                if (includedRowCount(member, PremiumInventoryType.PINEAPPLE_LIKE) != 1 ||
                    includedRowCount(member, PremiumInventoryType.BOOST) != 1 ||
                    anchorCount(member) != 1 ||
                    balance(member, PremiumInventoryType.PINEAPPLE_LIKE) != 5 - spends
                ) {
                    wrong++
                }
            }
            assertEquals(0, wrong, "iterations out of $ITERATIONS that did not grant exactly one period's allowance")
        }

    // ---- Swipe hot path and refunds -------------------------------------------------------------

    @Test
    fun aSwipePineappleLikeSpendsTheIncludedUnitInItsOwnTransaction() =
        runBlocking<Unit> {
            val member = user()
            val target = user()
            subscribe(member, SubscriptionTier.SOCIAL)
            buy(member, PremiumInventoryType.PINEAPPLE_LIKE, 2)
            val allowance = service.includedAllowance(member, PremiumInventoryType.PINEAPPLE_LIKE)
            assertEquals(1, allowance.quantity)

            val result =
                dataSource.connection.use { connection ->
                    connection.recordSwipeActionTransaction(
                        actionId = UUID.randomUUID(),
                        actorUserId = member,
                        targetUserId = target,
                        action = "pineapple_like",
                        noteText = null,
                        consumedInventoryId = null,
                        travelLocationId = null,
                        matchIdFactory = UUID::randomUUID,
                        spendInventory = SwipeInventorySpend.PINEAPPLE_LIKE,
                        includedAllowance = allowance,
                        now = clock.instant(),
                    )
                }

            assertTrue(result.recorded)
            assertEquals(0, includedLeft(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(2, purchasedLeft(member, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(includedRowId(member, PremiumInventoryType.PINEAPPLE_LIKE), consumedInventoryIdOf(member, target))
        }

    @Test
    fun aRefundedIncludedUnitGoesBackToThisWeeksAllowance() =
        runBlocking<Unit> {
            val member = user()
            subscribe(member, SubscriptionTier.SOCIAL)
            val spent = assertNotNull(service.consumePineappleLikeInventoryReturningId(member))
            assertEquals(0, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))

            assertTrue(service.refundPineappleLikeInventory(spent))

            assertEquals(1, balance(member, PremiumInventoryType.PINEAPPLE_LIKE))
        }

    // ---- Boost ----------------------------------------------------------------------------------

    @Test
    fun theMonthlyBoostIsActivatedOnceRespectsOneActiveBoostAndRefreshesNextMonth() =
        runBlocking<Unit> {
            val member = user()
            subscribe(member, SubscriptionTier.SELECT, endsAt = clock.instant().plus(Duration.ofDays(90)))
            buy(member, PremiumInventoryType.BOOST, 1)
            assertEquals(2, balance(member, PremiumInventoryType.BOOST))

            val first = service.activateBoost(member)
            assertFalse(first.alreadyActive)
            assertEquals(0, includedLeft(member, PremiumInventoryType.BOOST), "the included Boost goes first")
            assertEquals(1, purchasedLeft(member, PremiumInventoryType.BOOST))

            val again = service.activateBoost(member)
            assertTrue(again.alreadyActive, "one active Boost: a second tap reports the running one")
            assertEquals(first.activationId, again.activationId)
            assertEquals(1, purchasedLeft(member, PremiumInventoryType.BOOST))

            clock.advance(Duration.ofMinutes(31))
            service.activateBoost(member)
            assertEquals(0, purchasedLeft(member, PremiumInventoryType.BOOST), "the purchased Boost only once the included one is used")
            clock.advance(Duration.ofMinutes(31))
            assertFailsWith<SubscriptionException> { service.activateBoost(member) }

            clock.advance(Duration.ofDays(30))
            assertEquals(1, balance(member, PremiumInventoryType.BOOST))
            assertFalse(service.activateBoost(member).alreadyActive)
            assertEquals(0, balance(member, PremiumInventoryType.BOOST))
        }

    @Test
    fun aDoubleTapOnTheMonthlyBoostStartsOneAndSpendsOne() =
        runBlocking<Unit> {
            var wrong = 0
            repeat(ITERATIONS) {
                val member = user()
                subscribe(member, SubscriptionTier.SELECT)
                raceTogether({ service.activateBoost(member) }, { service.activateBoost(member) })
                if (activeBoostCount(member) != 1 ||
                    includedLeft(member, PremiumInventoryType.BOOST) != 0 ||
                    includedRowCount(member, PremiumInventoryType.BOOST) != 1
                ) {
                    wrong++
                }
            }
            assertEquals(0, wrong, "iterations out of $ITERATIONS that did not start exactly one included Boost")
        }

    // ---- Helpers --------------------------------------------------------------------------------

    private suspend fun balance(
        userId: UUID,
        type: String,
    ): Int =
        service
            .getInventory(userId)
            .balances
            .firstOrNull { it.inventoryType == type }
            ?.quantity ?: 0

    private fun user(): UUID {
        val id = UUID.randomUUID()
        update("INSERT INTO users (id, status) VALUES (?, 'active')", id)
        return id
    }

    private fun subscribe(
        userId: UUID,
        tier: String,
        startsAt: Instant = clock.instant(),
        endsAt: Instant = startsAt.plus(Duration.ofDays(90)),
    ): UUID {
        val id = UUID.randomUUID()
        update(
            """
            INSERT INTO subscriptions (id, user_id, platform, external_subscription_id, tier, status, current_period_start, current_period_end)
            VALUES (?, ?, 'dev', ?, ?, 'active', ?, ?)
            """.trimIndent(),
            id,
            userId,
            "allowance-$id",
            tier,
            Timestamp.from(startsAt),
            Timestamp.from(endsAt),
        )
        return id
    }

    private fun endSubscription(subscriptionId: UUID) {
        update("UPDATE subscriptions SET status = 'expired', updated_at = now() WHERE id = ?", subscriptionId)
    }

    private fun link(
        beneficiary: UUID,
        subscriber: UUID,
    ) {
        update(
            "INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at) VALUES (?, ?, ?, 'active', now())",
            UUID.randomUUID(),
            beneficiary,
            subscriber,
        )
    }

    private fun unlink(
        beneficiary: UUID,
        subscriber: UUID,
    ) {
        update(
            "UPDATE user_profile_links SET status = 'unlinked', unlinked_at = now() WHERE user_id = ? AND linked_user_id = ?",
            beneficiary,
            subscriber,
        )
    }

    private fun relink(
        beneficiary: UUID,
        subscriber: UUID,
    ) {
        update(
            "UPDATE user_profile_links SET status = 'active', unlinked_at = NULL WHERE user_id = ? AND linked_user_id = ?",
            beneficiary,
            subscriber,
        )
    }

    private fun buy(
        userId: UUID,
        type: String,
        quantity: Int,
    ) {
        update(
            "INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source) VALUES (?, ?, ?, ?, 'purchase')",
            UUID.randomUUID(),
            userId,
            type,
            quantity,
        )
    }

    private fun purchasedLeft(
        userId: UUID,
        type: String,
    ): Int =
        intQuery(
            "SELECT COALESCE(sum(quantity), 0)::integer FROM premium_inventory WHERE user_id = ? AND inventory_type = ? AND source = 'purchase'",
            userId,
            type,
        )

    /** Included units usable in the current period. */
    private fun includedLeft(
        userId: UUID,
        type: String,
    ): Int =
        intQuery(
            """
            SELECT COALESCE(sum(quantity), 0)::integer FROM premium_inventory
            WHERE user_id = ? AND inventory_type = ? AND allowance_period_start IS NOT NULL AND expires_at > ?
            """.trimIndent(),
            userId,
            type,
            Timestamp.from(clock.instant()),
        )

    private fun includedRowCount(
        userId: UUID,
        type: String? = null,
    ): Int =
        if (type == null) {
            intQuery(
                "SELECT COUNT(*)::integer FROM premium_inventory WHERE user_id = ? AND allowance_period_start IS NOT NULL",
                userId,
            )
        } else {
            intQuery(
                "SELECT COUNT(*)::integer FROM premium_inventory WHERE user_id = ? AND inventory_type = ? AND allowance_period_start IS NOT NULL",
                userId,
                type,
            )
        }

    private fun includedRowId(
        userId: UUID,
        type: String,
    ): UUID? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT id FROM premium_inventory WHERE user_id = ? AND inventory_type = ? AND allowance_period_start IS NOT NULL")
                .use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, type)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                }
        }

    private fun consumedInventoryIdOf(
        actor: UUID,
        target: UUID,
    ): UUID? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT consumed_inventory_id FROM swipe_actions WHERE actor_user_id = ? AND target_user_id = ?")
                .use { statement ->
                    statement.setObject(1, actor)
                    statement.setObject(2, target)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                }
        }

    private fun hasAnchor(userId: UUID): Boolean = anchorCount(userId) > 0

    private fun anchorCount(userId: UUID): Int = intQuery("SELECT COUNT(*)::integer FROM included_allowance_anchors WHERE user_id = ?", userId)

    private fun activeBoostCount(userId: UUID): Int = intQuery("SELECT COUNT(*)::integer FROM boost_activations WHERE user_id = ? AND status = 'active'", userId)

    private fun update(
        sql: String,
        vararg params: Any,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private fun intQuery(
        sql: String,
        vararg params: Any,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private companion object {
        const val ITERATIONS = 20
        const val CONCURRENT_REQUESTS = 6
    }
}

/** A clock the test moves by hand, so period boundaries are exact and nothing sleeps. */
private class SteppingClock(
    @Volatile private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}
