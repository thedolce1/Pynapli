package com.pynapli.app.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.EntitlementSource
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        StoreHeader(entitlement = state.entitlement)
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = state.tiers,
                        key = { tier -> tier.tier }
                    ) { tier ->
                        StoreTierRow(
                            tier = tier,
                            currentTier = state.currentTier,
                            pendingPurchaseTier = state.pendingPurchaseTier,
                            onPurchaseTier = onPurchaseTier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreHeader(entitlement: SubscriptionEntitlementDto?) {
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
    }
}

@Composable
private fun StoreTierRow(
    tier: SubscriptionTierDto,
    currentTier: String,
    pendingPurchaseTier: String?,
    onPurchaseTier: (SubscriptionTierDto) -> Unit
) {
    val isCurrent = tier.tier == currentTier
    val isFree = tier.tier == SubscriptionTier.FREE
    val purchaseInFlight = pendingPurchaseTier != null
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
                    text = tier.monthlyPriceUsdCents.priceLabel(),
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

private fun Int.priceLabel(): String {
    if (this <= 0) return "${'$'}0/mo"
    val dollars = this / 100
    val cents = (this % 100).toString().padStart(2, '0')
    return "${'$'}$dollars.$cents/mo"
}

private fun String?.sourceLabel(): String = when (this) {
    EntitlementSource.OWN_SUBSCRIPTION -> "Paid subscription active"
    EntitlementSource.LINKED_PARTNER_BENEFIT -> "Linked partner benefit active"
    EntitlementSource.PROMO -> "Promo entitlement active"
    EntitlementSource.ADMIN -> "Admin entitlement active"
    else -> "Free plan active"
}

@Suppress("unused")
private fun String.tierLabel(): String = when (this) {
    SubscriptionTier.FREE -> "Free"
    SubscriptionTier.SOCIAL -> "Social"
    SubscriptionTier.SELECT -> "Select"
    else -> this
}
