package com.pynapli.app.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.launch

@Composable
fun StoreScreen(
    stateHolder: StoreStateHolder = rememberStoreStateHolder(),
    modifier: Modifier = Modifier,
) {
    val state by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    StoreScreenContent(
        state = state,
        onPurchaseTier = { tier -> scope.launch { stateHolder.purchase(tier) } },
        onPurchaseProduct = { product -> scope.launch { stateHolder.purchaseProduct(product) } },
        onRestorePurchases = { scope.launch { stateHolder.restorePurchases() } },
        modifier = modifier,
    )
}

@Composable
private fun rememberStoreStateHolder(): StoreStateHolder = remember { StoreStateHolder(repository = SampleStoreRepository()) }

@Composable
private fun StoreScreenContent(
    state: StoreUiState,
    onPurchaseTier: (SubscriptionTierDto) -> Unit,
    onPurchaseProduct: (PremiumProductDto) -> Unit,
    onRestorePurchases: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        StoreHeader(
            entitlement = state.entitlement,
            isRestoringPurchases = state.isRestoringPurchases,
            restoreEnabled =
                state.tiers.any { it.playStoreProductId != null } &&
                    !state.isLoading &&
                    !state.isRestoringPurchases &&
                    state.pendingPurchaseTier == null &&
                    state.pendingPurchaseProductId == null,
            onRestorePurchases = onRestorePurchases,
        )
        StoreMessageRow(
            message = state.purchaseMessage,
            error = state.errorMessage.takeIf { state.tiers.isNotEmpty() },
        )

        when {
            state.isLoading && state.tiers.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            state.errorMessage != null && state.tiers.isEmpty() -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag(StoreTestTags.LIST),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "current-plan") {
                        CurrentPlanRow(entitlement = state.entitlement)
                    }

                    item(key = "tiers-title") {
                        StoreSectionTitle(
                            title = "Plans",
                            subtitle = "Safety stays free. Paid plans add reach, privacy, and insight.",
                        )
                    }

                    items(
                        items = state.tiers,
                        key = { tier -> tier.tier },
                    ) { tier ->
                        StoreTierRow(
                            tier = tier,
                            currentTier = state.currentTier,
                            pendingPurchaseTier = state.pendingPurchaseTier,
                            pendingPurchaseProductId = state.pendingPurchaseProductId,
                            isRestoringPurchases = state.isRestoringPurchases,
                            onPurchaseTier = onPurchaseTier,
                        )
                    }

                    if (state.premiumProducts.isNotEmpty()) {
                        item(key = "products-title") {
                            StoreSectionTitle(
                                title = "Boosts & extras",
                                subtitle = "Optional accelerators. Blocks, privacy, and recipient controls still apply.",
                            )
                        }
                    }

                    items(
                        items = state.premiumProducts,
                        key = { product -> product.productId },
                    ) { product ->
                        StoreProductRow(
                            product = product,
                            balance = state.balanceFor(product.inventoryType),
                            pendingPurchaseProductId = state.pendingPurchaseProductId,
                            pendingPurchaseTier = state.pendingPurchaseTier,
                            isRestoringPurchases = state.isRestoringPurchases,
                            onPurchaseProduct = onPurchaseProduct,
                        )
                    }
                }
            }
        }
    }
}
