package com.pynapli.app.store

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumInventoryBalanceDto
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreScreenInteractionTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun storeShowsPlansProductsAndRefreshesInventoryAfterPurchase() =
        runComposeUiTest {
            val repository = RefreshingStoreRepository()
            val gateway = RecordingStorePurchaseGateway()
            val stateHolder = StoreStateHolder(repository = repository, purchaseGateway = gateway)

            setContent {
                PynapliTheme(themeMode = "dark") {
                    Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                        StoreScreen(stateHolder = stateHolder)
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000) { repository.loadCount == 1 }
            onNodeWithText("Plans").assertExists()
            onNodeWithText("Pynapli Select").assertExists()
            onNodeWithTag(StoreTestTags.LIST).performScrollToNode(hasText("Boosts & extras"))
            onNodeWithText("Boosts & extras").assertExists()
            onNodeWithText("Boost").assertExists()
            onNodeWithText("Balance: 0").assertExists()

            onNodeWithText("\$2.99").performClick()
            waitUntil(timeoutMillis = 5_000) { gateway.productPurchaseCount == 1 }
            waitUntil(timeoutMillis = 5_000) { stateHolder.state.value.balanceFor(PremiumInventoryType.BOOST) == 1 }

            assertEquals(2, repository.loadCount)
            onNodeWithText("Boost added.").assertExists()
            onNodeWithText("Balance: 1").assertExists()
        }
}

private class RefreshingStoreRepository : StoreRepository {
    var loadCount = 0
        private set

    override suspend fun loadStore(): AppResult<StoreUi> {
        loadCount += 1
        return AppResult.Success(
            StoreUi(
                tiers = listOf(freeTier(), socialTier(), selectTier()),
                premiumProducts = listOf(boostProduct()),
                balances =
                    if (loadCount > 1) {
                        listOf(PremiumInventoryBalanceDto(PremiumInventoryType.BOOST, quantity = 1))
                    } else {
                        emptyList()
                    },
                entitlement = entitlement(),
            ),
        )
    }
}

private class RecordingStorePurchaseGateway : StorePurchaseGateway {
    var productPurchaseCount = 0
        private set

    override suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult> = AppResult.Success(StorePurchaseResult(message = "${tier.displayName} active."))

    override suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult> {
        productPurchaseCount += 1
        return AppResult.Success(StorePurchaseResult(message = "${product.displayName} added."))
    }

    override suspend fun restorePurchases(): AppResult<StorePurchaseResult> = AppResult.Success(StorePurchaseResult(message = "Store refreshed."))
}

private fun freeTier(): SubscriptionTierDto =
    SubscriptionTierDto(
        tier = SubscriptionTier.FREE,
        displayName = "Pynapli Free",
        monthlyPriceUsdCents = 0,
        linkedPartnerIncluded = false,
        features = listOf("Grid browsing", "Swipe matching"),
    )

private fun socialTier(): SubscriptionTierDto =
    SubscriptionTierDto(
        tier = SubscriptionTier.SOCIAL,
        displayName = "Pynapli Social",
        monthlyPriceUsdCents = 499,
        linkedPartnerIncluded = true,
        features = listOf("Expanded grid", "Saved filters"),
        playStoreProductId = SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
    )

private fun selectTier(): SubscriptionTierDto =
    SubscriptionTierDto(
        tier = SubscriptionTier.SELECT,
        displayName = "Pynapli Select",
        monthlyPriceUsdCents = 999,
        linkedPartnerIncluded = true,
        features = listOf("Full Activity reveal", "Intro Notes"),
        playStoreProductId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
    )

private fun boostProduct(): PremiumProductDto =
    PremiumProductDto(
        productId = SubscriptionProductId.BOOST_SINGLE,
        inventoryType = PremiumInventoryType.BOOST,
        displayName = "Boost",
        description = "Increase visibility for 30 minutes.",
        priceUsdCents = 299,
        quantity = 1,
        features = listOf("30-minute visibility lift"),
    )

private fun entitlement(): SubscriptionEntitlementDto =
    SubscriptionEntitlementDto(
        tier = SubscriptionTier.FREE,
        source = EntitlementSource.FREE,
        displayName = "Pynapli Free",
        linkedPartnerIncluded = false,
    )
