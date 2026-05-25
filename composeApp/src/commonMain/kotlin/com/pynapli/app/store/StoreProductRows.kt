package com.pynapli.app.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto

@Composable
internal fun StoreTierRow(
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
internal fun StoreProductRow(
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
