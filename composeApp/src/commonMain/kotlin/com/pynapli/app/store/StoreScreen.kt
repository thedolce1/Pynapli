package com.pynapli.app.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliLayout
import com.pynapli.app.design.PynapliMaxWidthColumn
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.launch

@Composable
fun StoreScreen(
    stateHolder: StoreStateHolder,
    modifier: Modifier = Modifier,
    // Web is read-only (founder-locked): catalog + balances visible, no purchase controls.
    purchasesAvailable: Boolean = true,
    // The section a paywall opened the Store for; the list scrolls there once it has loaded.
    focus: StorePaywallFocus? = null,
    // Shown as a Close button when the Store is a sheet.
    onClose: (() -> Unit)? = null,
) {
    val state by stateHolder.state.collectAsState()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    StoreScreenContent(
        state = state,
        onPurchaseTier = { tier -> scope.launch { stateHolder.purchase(tier) } },
        onPurchaseProduct = { product -> scope.launch { stateHolder.purchaseProduct(product) } },
        onRestorePurchases = { scope.launch { stateHolder.restorePurchases() } },
        onUseBoost = { scope.launch { stateHolder.useBoost() } },
        onManageSubscription = { uriHandler.openUri(MANAGE_SUBSCRIPTIONS_URL) },
        modifier = modifier,
        purchasesAvailable = purchasesAvailable,
        focus = focus,
        onClose = onClose,
    )
}

@Composable
private fun StoreScreenContent(
    state: StoreUiState,
    onPurchaseTier: (SubscriptionTierDto) -> Unit,
    onPurchaseProduct: (PremiumProductDto) -> Unit,
    onRestorePurchases: () -> Unit,
    modifier: Modifier = Modifier,
    purchasesAvailable: Boolean = true,
    onUseBoost: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
    focus: StorePaywallFocus? = null,
    onClose: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val focusIndex = focus?.let { storeListIndexFor(it, state, purchasesAvailable) }
    LaunchedEffect(focusIndex) {
        focusIndex?.let { listState.scrollToItem(it) }
    }
    // The flagship paid plan (highest monthly price) gets the "Recommended" hero emphasis.
    val heroTier =
        state.tiers
            .filter { it.monthlyPriceUsdCents > 0 }
            .maxByOrNull { it.monthlyPriceUsdCents }
            ?.tier
    PynapliMaxWidthColumn(
        maxWidth = PynapliContentWidth.Feed,
        modifier = modifier,
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
            showRestore = purchasesAvailable,
            onClose = onClose,
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
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag(StoreTestTags.LIST),
                    contentPadding = PaddingValues(horizontal = PynapliLayout.ScreenPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "current-plan") {
                        CurrentPlanRow(entitlement = state.entitlement, onManageSubscription = onManageSubscription)
                    }

                    if (!purchasesAvailable) {
                        item(key = "manage-on-mobile") {
                            StoreManageOnMobileRow()
                        }
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
                            isHero = tier.tier == heroTier,
                            pendingPurchaseTier = state.pendingPurchaseTier,
                            pendingPurchaseProductId = state.pendingPurchaseProductId,
                            isRestoringPurchases = state.isRestoringPurchases,
                            onPurchaseTier = onPurchaseTier,
                            purchasesAvailable = purchasesAvailable,
                            localizedPrices = state.localizedPrices,
                        )
                    }

                    // Subscription benefit note sits with the tiers (handoff order), above the
                    // one-time Boosts & extras.
                    item(key = "linked-partner-benefit") {
                        StoreLinkedPartnerBenefitRow()
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
                            purchasesAvailable = purchasesAvailable,
                            activeBoostEndsAt = state.activeBoostEndsAt.takeIf { product.inventoryType == PremiumInventoryType.BOOST },
                            isActivatingBoost = state.isActivatingBoost,
                            onUseBoost = onUseBoost.takeIf { product.inventoryType == PremiumInventoryType.BOOST },
                            nextExpiresAt = state.balances.firstOrNull { it.inventoryType == product.inventoryType }?.nextExpiresAt,
                        )
                    }
                }
            }
        }
    }
}

// Google Play's subscription management page for this app (cancel, change plan, payment method).
internal const val MANAGE_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions?package=com.pynapli.app"
