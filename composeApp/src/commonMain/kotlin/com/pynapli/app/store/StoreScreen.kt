package com.pynapli.app.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.launch

@Composable
fun StoreScreen(
    stateHolder: StoreStateHolder = rememberStoreStateHolder(),
    modifier: Modifier = Modifier
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
        modifier = modifier
    )
}

@Composable
private fun rememberStoreStateHolder(): StoreStateHolder =
    remember { StoreStateHolder(repository = SampleStoreRepository()) }

@Composable
private fun StoreScreenContent(
    state: StoreUiState,
    onPurchaseTier: (SubscriptionTierDto) -> Unit,
    onPurchaseProduct: (PremiumProductDto) -> Unit,
    onRestorePurchases: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        StoreHeader(
            entitlement = state.entitlement,
            isRestoringPurchases = state.isRestoringPurchases,
            restoreEnabled = state.tiers.any { it.playStoreProductId != null } &&
                !state.isLoading &&
                !state.isRestoringPurchases &&
                state.pendingPurchaseTier == null &&
                state.pendingPurchaseProductId == null,
            onRestorePurchases = onRestorePurchases
        )
        StoreMessageRow(
            message = state.purchaseMessage,
            error = state.errorMessage.takeIf { state.tiers.isNotEmpty() }
        )

        when {
            state.isLoading && state.tiers.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            state.errorMessage != null && state.tiers.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(StoreTestTags.LIST),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "current-plan") {
                        CurrentPlanRow(entitlement = state.entitlement)
                    }

                    item(key = "tiers-title") {
                        StoreSectionTitle(
                            title = "Plans",
                            subtitle = "Safety stays free. Paid plans add reach, privacy, and insight."
                        )
                    }

                    items(
                        items = state.tiers,
                        key = { tier -> tier.tier }
                    ) { tier ->
                        StoreTierRow(
                            tier = tier,
                            currentTier = state.currentTier,
                            pendingPurchaseTier = state.pendingPurchaseTier,
                            pendingPurchaseProductId = state.pendingPurchaseProductId,
                            isRestoringPurchases = state.isRestoringPurchases,
                            onPurchaseTier = onPurchaseTier
                        )
                    }

                    if (state.premiumProducts.isNotEmpty()) {
                        item(key = "products-title") {
                            StoreSectionTitle(
                                title = "Boosts & extras",
                                subtitle = "Optional accelerators. Blocks, privacy, and recipient controls still apply."
                            )
                        }
                    }

                    items(
                        items = state.premiumProducts,
                        key = { product -> product.productId }
                    ) { product ->
                        StoreProductRow(
                            product = product,
                            balance = state.balanceFor(product.inventoryType),
                            pendingPurchaseProductId = state.pendingPurchaseProductId,
                            pendingPurchaseTier = state.pendingPurchaseTier,
                            isRestoringPurchases = state.isRestoringPurchases,
                            onPurchaseProduct = onPurchaseProduct
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreHeader(
    entitlement: SubscriptionEntitlementDto?,
    isRestoringPurchases: Boolean,
    restoreEnabled: Boolean,
    onRestorePurchases: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.ShoppingBag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Store",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = entitlement?.source.sourceLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(
            onClick = onRestorePurchases,
            enabled = restoreEnabled
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isRestoringPurchases) "Restoring" else "Restore")
        }
    }
}

@Composable
private fun CurrentPlanRow(entitlement: SubscriptionEntitlementDto?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entitlement?.displayName ?: "Pynapli Free",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (entitlement?.linkedPartnerIncluded == true) {
                    "Linked partner included"
                } else {
                    "Core discovery and safety included"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = (entitlement?.tier ?: SubscriptionTier.FREE).tierLabel(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun StoreSectionTitle(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StoreTierRow(
    tier: SubscriptionTierDto,
    currentTier: String,
    pendingPurchaseTier: String?,
    pendingPurchaseProductId: String?,
    isRestoringPurchases: Boolean,
    onPurchaseTier: (SubscriptionTierDto) -> Unit
) {
    val isCurrent = tier.tier == currentTier
    val isFree = tier.tier == SubscriptionTier.FREE
    val purchaseInFlight = pendingPurchaseTier != null || pendingPurchaseProductId != null || isRestoringPurchases
    val isPurchasing = pendingPurchaseTier == tier.tier
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                }
            )
            .border(
                width = 1.dp,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tier.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = tier.monthlyPriceUsdCents.monthlyPriceLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (isCurrent) {
                OutlinedButton(onClick = {}, enabled = false) {
                    Text("Current")
                }
            } else if (isFree) {
                OutlinedButton(onClick = {}, enabled = false) {
                    Text("Included")
                }
            } else {
                Button(
                    onClick = { onPurchaseTier(tier) },
                    enabled = !purchaseInFlight && tier.playStoreProductId != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(if (isPurchasing) "Opening" else "Choose")
                }
            }
        }

        tier.features.forEach { feature ->
            StoreFeatureRow(label = feature)
        }

        StoreFeatureRow(
            label = if (tier.linkedPartnerIncluded) {
                "Linked partner included"
            } else {
                "Single account entitlement"
            }
        )
        StoreFeatureRow(
            label = if (tier.adSupported) "Ad-supported" else "No ads"
        )
    }
}

@Composable
private fun StoreProductRow(
    product: PremiumProductDto,
    balance: Int,
    pendingPurchaseProductId: String?,
    pendingPurchaseTier: String?,
    isRestoringPurchases: Boolean,
    onPurchaseProduct: (PremiumProductDto) -> Unit
) {
    val purchaseInFlight = pendingPurchaseProductId != null || pendingPurchaseTier != null || isRestoringPurchases
    val isPurchasing = pendingPurchaseProductId == product.productId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = product.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Balance: $balance",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Button(
                onClick = { onPurchaseProduct(product) },
                enabled = !purchaseInFlight,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(if (isPurchasing) "Adding" else product.priceUsdCents.storePriceLabel(product.quantity))
            }
        }

        product.features.forEach { feature ->
            StoreFeatureRow(label = feature)
        }
    }
}

@Composable
private fun StoreMessageRow(
    message: String?,
    error: String?
) {
    val text = error ?: message ?: return
    val color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StoreFeatureRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp)
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun Int.monthlyPriceLabel(): String {
    if (this <= 0) return "${'$'}0/mo"
    val dollars = this / 100
    val cents = (this % 100).toString().padStart(2, '0')
    return "${'$'}$dollars.$cents/mo"
}

private fun Int.storePriceLabel(quantity: Int): String {
    val dollars = this / 100
    val cents = (this % 100).toString().padStart(2, '0')
    val price = "${'$'}$dollars.$cents"
    return if (quantity > 1) "$quantity for $price" else price
}

private fun String?.sourceLabel(): String = when (this) {
    EntitlementSource.OWN_SUBSCRIPTION -> "Paid subscription active"
    EntitlementSource.LINKED_PARTNER_BENEFIT -> "Linked partner benefit active"
    EntitlementSource.PROMO -> "Promo entitlement active"
    EntitlementSource.ADMIN -> "Admin entitlement active"
    else -> "Free plan active"
}

private fun String.tierLabel(): String = when (this) {
    SubscriptionTier.FREE -> "Free"
    SubscriptionTier.SOCIAL -> "Social"
    SubscriptionTier.SELECT -> "Select"
    else -> this
}

internal object StoreTestTags {
    const val LIST = "store-list"
}
