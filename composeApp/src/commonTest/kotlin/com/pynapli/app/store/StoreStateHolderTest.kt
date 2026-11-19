package com.pynapli.app.store

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumInventoryBalanceDto
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoreStateHolderTest {
    @Test
    fun aPurchaseFinishedWhileTheAppWasAwayIsConfirmedWhenTheStoreOpens() =
        runTest {
            val repository = FakeStoreRepository(firstEntitlementTier = SubscriptionTier.FREE, refreshedEntitlementTier = SubscriptionTier.SELECT)
            val gateway = FakeStorePurchaseGateway().apply { unfinishedPurchase = true }
            val stateHolder = StoreStateHolder(repository = repository, purchaseGateway = gateway)

            stateHolder.load()

            assertEquals(SubscriptionTier.SELECT, stateHolder.state.value.currentTier)
            assertEquals("Your purchase is active.", stateHolder.state.value.purchaseMessage)
        }

    @Test
    fun plansShowThePriceTheStoreWillCharge() =
        runTest {
            val repository = FakeStoreRepository(firstEntitlementTier = SubscriptionTier.FREE, refreshedEntitlementTier = SubscriptionTier.FREE)
            val stateHolder = StoreStateHolder(repository = repository, purchaseGateway = FakeStorePurchaseGateway())

            stateHolder.load()

            assertEquals(mapOf("pynapli_select_monthly" to "CA$15.99"), stateHolder.state.value.localizedPrices)
        }

    @Test
    fun storeDatesReadAsShortMonthAndDay() {
        assertEquals(true, "2026-12-12T12:00:00Z".storeDateLabel()?.startsWith("Dec "))
        assertEquals(null, "not a date".storeDateLabel())
    }

    @Test
    fun usingABoostTurnsItOnAndRefreshesTheBalance() =
        runTest {
            val repository = FakeStoreRepository(firstEntitlementTier = SubscriptionTier.FREE, refreshedEntitlementTier = SubscriptionTier.FREE)
            val stateHolder = StoreStateHolder(repository = repository)
            stateHolder.load()

            stateHolder.useBoost()

            assertEquals(1, repository.boostActivations)
            assertEquals("Boost on: you're shown first to people nearby for the next 30 minutes.", stateHolder.state.value.purchaseMessage)
            assertEquals(2, repository.loadCount)

            stateHolder.useBoost()
            assertEquals("Your Boost is already on.", stateHolder.state.value.purchaseMessage)
        }

    @Test
    fun aBoostEndTimeReadsAsAClockTimeAndDisappearsOnceOver() {
        @OptIn(kotlin.time.ExperimentalTime::class)
        val now = kotlin.time.Instant.parse("2026-11-12T20:00:00Z")
        assertEquals(null, "2026-11-12T19:59:00Z".boostEndsLabel(now))
        assertEquals(null, "not a time".boostEndsLabel(now))
        assertEquals(5, "2026-11-12T20:30:00Z".boostEndsLabel(now)?.length)
    }

    @Test
    fun purchaseClearsPendingAndRefreshesEntitlement() =
        runTest {
            val repository =
                FakeStoreRepository(
                    firstEntitlementTier = SubscriptionTier.FREE,
                    refreshedEntitlementTier = SubscriptionTier.SELECT,
                )
            val gateway = FakeStorePurchaseGateway()
            val stateHolder = StoreStateHolder(repository = repository, purchaseGateway = gateway)

            stateHolder.load()
            stateHolder.purchase(selectTier())

            val state = stateHolder.state.value
            assertEquals(2, repository.loadCount)
            assertEquals(1, gateway.purchaseCount)
            assertNull(state.pendingPurchaseTier)
            assertNull(state.pendingPurchaseProductId)
            assertEquals("Purchase sent for verification.", state.purchaseMessage)
            assertEquals(SubscriptionTier.SELECT, state.currentTier)
        }

    @Test
    fun productPurchaseClearsPendingAndRefreshesBalances() =
        runTest {
            val repository =
                FakeStoreRepository(
                    firstEntitlementTier = SubscriptionTier.SELECT,
                    refreshedEntitlementTier = SubscriptionTier.SELECT,
                )
            val gateway = FakeStorePurchaseGateway()
            val stateHolder = StoreStateHolder(repository = repository, purchaseGateway = gateway)

            stateHolder.load()
            stateHolder.purchaseProduct(boostProduct())

            val state = stateHolder.state.value
            assertEquals(2, repository.loadCount)
            assertEquals(1, gateway.productPurchaseCount)
            assertNull(state.pendingPurchaseProductId)
            assertEquals("Inventory added.", state.purchaseMessage)
            assertEquals(1, state.balanceFor(PremiumInventoryType.BOOST))
        }

    @Test
    fun restoreClearsPendingAndRefreshesEntitlement() =
        runTest {
            val repository =
                FakeStoreRepository(
                    firstEntitlementTier = SubscriptionTier.FREE,
                    refreshedEntitlementTier = SubscriptionTier.SOCIAL,
                )
            val gateway = FakeStorePurchaseGateway()
            val stateHolder = StoreStateHolder(repository = repository, purchaseGateway = gateway)

            stateHolder.load()
            stateHolder.restorePurchases()

            val state = stateHolder.state.value
            assertEquals(2, repository.loadCount)
            assertEquals(1, gateway.restoreCount)
            assertEquals(false, state.isRestoringPurchases)
            assertEquals("Subscription restored.", state.purchaseMessage)
            assertEquals(SubscriptionTier.SOCIAL, state.currentTier)
        }
}

private class FakeStoreRepository(
    private val firstEntitlementTier: String,
    private val refreshedEntitlementTier: String,
) : StoreRepository {
    var loadCount = 0
        private set
    var boostActivations = 0
        private set

    override suspend fun activateBoost(): AppResult<com.pynapli.shared.network.dto.BoostActivationDto> {
        boostActivations += 1
        return AppResult.Success(
            com.pynapli.shared.network.dto.BoostActivationDto(
                activationId = "boost-1",
                boostType = "boost",
                startsAt = "2026-11-12T20:00:00Z",
                endsAt = "2026-11-12T20:30:00Z",
                alreadyActive = boostActivations > 1,
            ),
        )
    }

    override suspend fun loadStore(): AppResult<StoreUi> {
        loadCount += 1
        val tier = if (loadCount == 1) firstEntitlementTier else refreshedEntitlementTier
        return AppResult.Success(
            StoreUi(
                tiers = listOf(selectTier()),
                premiumProducts = listOf(boostProduct()),
                balances =
                    if (loadCount == 1) {
                        emptyList()
                    } else {
                        listOf(PremiumInventoryBalanceDto(PremiumInventoryType.BOOST, quantity = 1))
                    },
                entitlement = entitlement(tier),
            ),
        )
    }
}

private class FakeStorePurchaseGateway : StorePurchaseGateway {
    override suspend fun localizedPrices(productIds: List<String>): Map<String, String> = productIds.associateWith { "CA$15.99" }

    var unfinishedPurchase = false

    override suspend fun syncUnfinishedPurchases(): Boolean = unfinishedPurchase.also { unfinishedPurchase = false }

    var purchaseCount = 0
        private set
    var productPurchaseCount = 0
        private set
    var restoreCount = 0
        private set

    override suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult> {
        purchaseCount += 1
        return AppResult.Success(StorePurchaseResult(message = "Purchase sent for verification."))
    }

    override suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult> {
        productPurchaseCount += 1
        return AppResult.Success(StorePurchaseResult(message = "Inventory added."))
    }

    override suspend fun restorePurchases(): AppResult<StorePurchaseResult> {
        restoreCount += 1
        return AppResult.Success(StorePurchaseResult(message = "Subscription restored."))
    }
}

private fun selectTier(): SubscriptionTierDto =
    SubscriptionTierDto(
        tier = SubscriptionTier.SELECT,
        displayName = "Pynapli Select",
        monthlyPriceUsdCents = 999,
        linkedPartnerIncluded = true,
        features = listOf("5 Pineapple Likes every week"),
        playStoreProductId = "pynapli_select_monthly",
    )

private fun boostProduct(): PremiumProductDto =
    PremiumProductDto(
        productId = SubscriptionProductId.BOOST_SINGLE,
        inventoryType = PremiumInventoryType.BOOST,
        displayName = "Boost",
        description = "Increase visibility for 30 minutes.",
        priceUsdCents = 299,
        quantity = 1,
        features = listOf("Visibility lift"),
    )

private fun entitlement(tier: String): SubscriptionEntitlementDto =
    SubscriptionEntitlementDto(
        tier = tier,
        source = if (tier == SubscriptionTier.FREE) EntitlementSource.FREE else EntitlementSource.OWN_SUBSCRIPTION,
        displayName = if (tier == SubscriptionTier.FREE) "Pynapli Free" else "Pynapli Select",
        linkedPartnerIncluded = tier != SubscriptionTier.FREE,
    )
