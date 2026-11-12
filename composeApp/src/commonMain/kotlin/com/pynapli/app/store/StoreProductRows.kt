package com.pynapli.app.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun StoreTierRow(
    tier: SubscriptionTierDto,
    currentTier: String,
    pendingPurchaseTier: String?,
    pendingPurchaseProductId: String?,
    isRestoringPurchases: Boolean,
    onPurchaseTier: (SubscriptionTierDto) -> Unit,
    isHero: Boolean = false,
    // Web is read-only (founder-locked): plans stay visible, buy buttons don't render;
    // StoreManageOnMobileRow explains where purchases live.
    purchasesAvailable: Boolean = true,
) {
    val isCurrent = tier.tier == currentTier
    val isFree = tier.tier == SubscriptionTier.FREE
    // Hero (flagship) emphasis only matters as an upsell — drop it once the viewer is on the plan.
    val showHero = isHero && !isCurrent
    val purchaseInFlight = pendingPurchaseTier != null || pendingPurchaseProductId != null || isRestoringPurchases
    val isPurchasing = pendingPurchaseTier == tier.tier
    val background =
        when {
            isCurrent -> PynapliColors.Accent.copy(alpha = 0.12f)
            showHero -> PynapliColors.Accent.copy(alpha = 0.06f)
            else -> PynapliColors.SurfaceRaised
        }
    val borderColor =
        when {
            isCurrent -> PynapliColors.Accent.copy(alpha = 0.55f)
            showHero -> PynapliColors.Accent.copy(alpha = 0.40f)
            else -> PynapliColors.Border
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(background)
                .border(
                    width = if (showHero) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = PynapliShape.CardShape,
                ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showHero) {
            Text(
                text = "Recommended",
                color = PynapliColors.Accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .clip(PynapliShape.PillShape)
                        .background(PynapliColors.Accent.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tier.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tier.monthlyPriceUsdCents.monthlyPriceLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (isCurrent) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.handCursor(enabled = false)) {
                    Text("Current")
                }
            } else if (isFree) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.handCursor(enabled = false)) {
                    Text("Included")
                }
            } else if (purchasesAvailable) {
                Button(
                    onClick = { onPurchaseTier(tier) },
                    enabled = !purchaseInFlight && tier.playStoreProductId != null,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    modifier = Modifier.handCursor(enabled = !purchaseInFlight && tier.playStoreProductId != null),
                ) {
                    Text(if (isPurchasing) "Opening" else "Choose")
                }
            }
        }

        tier.features.forEach { feature ->
            StoreFeatureRow(label = feature)
        }

        StoreFeatureRow(
            label =
                if (tier.linkedPartnerIncluded) {
                    "Linked partner included"
                } else {
                    "Single account entitlement"
                },
        )
        StoreFeatureRow(
            label = if (tier.adSupported) "Ad-supported" else "No ads",
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
    onPurchaseProduct: (PremiumProductDto) -> Unit,
    purchasesAvailable: Boolean = true,
    // Boost row only: when the running Boost ends, and how to turn one on from the balance.
    activeBoostEndsAt: String? = null,
    isActivatingBoost: Boolean = false,
    onUseBoost: (() -> Unit)? = null,
) {
    val purchaseInFlight = pendingPurchaseProductId != null || pendingPurchaseTier != null || isRestoringPurchases
    val isPurchasing = pendingPurchaseProductId == product.productId
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.SurfaceRaised)
                .border(
                    width = 1.dp,
                    color = PynapliColors.Border,
                    shape = PynapliShape.CardShape,
                ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = product.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Balance: $balance",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            if (purchasesAvailable) {
                Button(
                    onClick = { onPurchaseProduct(product) },
                    enabled = !purchaseInFlight,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    modifier = Modifier.handCursor(enabled = !purchaseInFlight),
                ) {
                    Text(if (isPurchasing) "Adding" else product.priceUsdCents.storePriceLabel(product.quantity))
                }
            } else {
                Text(
                    text = product.priceUsdCents.storePriceLabel(product.quantity),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (onUseBoost != null) {
            val endsAt = activeBoostEndsAt?.boostEndsLabel()
            when {
                endsAt != null ->
                    Text(
                        text = "Boost on until $endsAt",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )

                balance > 0 ->
                    OutlinedButton(
                        onClick = onUseBoost,
                        enabled = !isActivatingBoost,
                        modifier = Modifier.fillMaxWidth().handCursor(enabled = !isActivatingBoost),
                    ) {
                        Text(if (isActivatingBoost) "Turning on" else "Use a Boost now")
                    }
            }
        }

        product.features.forEach { feature ->
            StoreFeatureRow(label = feature)
        }
    }
}

/** The local "21:40" a Boost ends at, or null once it has ended (or the time can't be read). */
@OptIn(kotlin.time.ExperimentalTime::class)
internal fun String.boostEndsLabel(
    now: kotlin.time.Instant =
        kotlin.time.Clock.System
            .now(),
): String? =
    runCatching {
        val instant = kotlin.time.Instant.parse(this)
        if (instant <= now) return null
        val dt = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    }.getOrNull()

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
