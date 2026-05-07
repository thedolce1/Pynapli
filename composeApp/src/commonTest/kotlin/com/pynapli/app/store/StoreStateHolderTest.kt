package com.pynapli.app.store

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoreStateHolderTest {
    @Test
    fun purchaseClearsPendingAndRefreshesEntitlement() = runBlocking {
        val repository = FakeStoreRepository(
            firstEntitlementTier = SubscriptionTier.FREE,
            refreshedEntitlementTier = SubscriptionTier.SELECT
        )
        val gateway = FakeStorePurchaseGateway()
        val stateHolder = StoreStateHolder(repository = repository, purchaseGateway = gateway)

        stateHolder.load()
        stateHolder.purchase(selectTier())

        val state = stateHolder.state.value
        assertEquals(2, repository.loadCount)
        assertEquals(1, gateway.purchaseCount)
        assertNull(state.pendingPurchaseTier)
        assertEquals("Purchase sent for verification.", state.purchaseMessage)
        assertEquals(SubscriptionTier.SELECT, state.currentTier)
    }

    @Test
    fun restoreClearsPendingAndRefreshesEntitlement() = runBlocking {
        val repository = FakeStoreRepository(
            firstEntitlementTier = SubscriptionTier.FREE,
            refreshedEntitlementTier = SubscriptionTier.SOCIAL
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
    private val refreshedEntitlementTier: String
) : StoreRepository {
    var loadCount = 0
        private set

    override suspend fun loadStore(): AppResult<StoreUi> {
        loadCount += 1
        val tier = if (loadCount == 1) firstEntitlementTier else refreshedEntitlementTier
        return AppResult.Success(
            StoreUi(
                tiers = listOf(selectTier()),
                entitlement = entitlement(tier)
            )
        )
    }
}

private class FakeStorePurchaseGateway : StorePurchaseGateway {
    var purchaseCount = 0
        private set
    var restoreCount = 0
        private set

    override suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult> {
        purchaseCount += 1
        return AppResult.Success(StorePurchaseResult(message = "Purchase sent for verification."))
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
        adSupported = false,
        linkedPartnerIncluded = true,
        features = listOf("Intro Notes"),
        playStoreProductId = "pynapli_select_monthly"
    )

private fun entitlement(tier: String): SubscriptionEntitlementDto =
    SubscriptionEntitlementDto(
        tier = tier,
        source = if (tier == SubscriptionTier.FREE) EntitlementSource.FREE else EntitlementSource.OWN_SUBSCRIPTION,
        displayName = if (tier == SubscriptionTier.FREE) "Pynapli Free" else "Pynapli Select",
        adSupported = tier == SubscriptionTier.FREE,
        linkedPartnerIncluded = tier != SubscriptionTier.FREE
    )
