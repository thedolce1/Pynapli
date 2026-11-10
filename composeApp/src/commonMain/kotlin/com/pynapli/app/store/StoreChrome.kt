package com.pynapli.app.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import com.pynapli.app.design.PynapliHeaderTokens
import com.pynapli.app.design.PynapliScreenHeader
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Check
import com.pynapli.app.design.icons.filled.Link
import com.pynapli.app.design.icons.filled.Refresh
import com.pynapli.app.design.icons.filled.ShoppingBag
import com.pynapli.app.design.icons.filled.Smartphone
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier

@Composable
internal fun StoreHeader(
    entitlement: SubscriptionEntitlementDto?,
    isRestoringPurchases: Boolean,
    restoreEnabled: Boolean,
    onRestorePurchases: () -> Unit,
    showRestore: Boolean = true,
) {
    PynapliScreenHeader(
        leading = { StoreIconBadge() },
        title = "Store",
        subtitle = entitlement?.source.sourceLabel(),
        actions = {
            if (showRestore) {
                OutlinedButton(
                    onClick = onRestorePurchases,
                    enabled = restoreEnabled,
                    modifier = Modifier.handCursor(enabled = restoreEnabled),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRestoringPurchases) "Restoring" else "Restore")
                }
            }
        },
    )
}

/**
 * Read-only Store explainer (WEB_PWA_GAMEPLAN.md W5.3, founder-locked): the web shows
 * plans and balances but purchases live in the mobile app; entitlements are
 * server-validated so they stay in sync everywhere automatically.
 */
@Composable
internal fun StoreManageOnMobileRow() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.SurfaceRaised)
                .border(1.dp, PynapliColors.Border, PynapliShape.CardShape)
                .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(PynapliShape.CardShape)
                    .background(PynapliColors.Accent.copy(alpha = 0.10f))
                    .border(1.dp, PynapliColors.Accent.copy(alpha = 0.22f), PynapliShape.CardShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Smartphone,
                contentDescription = null,
                tint = PynapliColors.Accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Purchases live in the mobile app",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Your plan and balances stay in sync here. To upgrade or add boosts, open Pynapli on your phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StoreIconBadge() {
    Box(
        modifier =
            Modifier
                .size(PynapliHeaderTokens.AvatarSize)
                .clip(CircleShape)
                .background(PynapliColors.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ShoppingBag,
            contentDescription = null,
            tint = PynapliColors.Accent,
            modifier = Modifier.size(23.dp),
        )
    }
}

@Composable
internal fun CurrentPlanRow(entitlement: SubscriptionEntitlementDto?) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.SurfaceRaised)
                .border(
                    width = 1.dp,
                    color = PynapliColors.Border,
                    shape = PynapliShape.CardShape,
                ).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entitlement?.displayName ?: "Pynapli Free",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    if (entitlement?.linkedPartnerIncluded == true) {
                        "Linked partner included"
                    } else {
                        "Core discovery and safety included"
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = (entitlement?.tier ?: SubscriptionTier.FREE).tierLabel(),
            color = PynapliColors.Accent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun StoreSectionTitle(
    title: String,
    subtitle: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun StoreMessageRow(
    message: String?,
    error: String?,
) {
    val text = error ?: message ?: return
    val color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.SurfaceRaised)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun StoreFeatureRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Linked-partner benefit callout (handoff `StoreScreen`): a dedicated explainer for the core Pynapli
 * differentiator — one paid tier covers an active linked partner — rather than only surfacing it as a
 * single perk line. Accent link-icon disc + headline + subtext, on the shared raised-card treatment.
 */
@Composable
internal fun StoreLinkedPartnerBenefitRow() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.SurfaceRaised)
                .border(1.dp, PynapliColors.Border, PynapliShape.CardShape)
                .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(PynapliShape.CardShape)
                    .background(PynapliColors.Accent.copy(alpha = 0.10f))
                    .border(1.dp, PynapliColors.Accent.copy(alpha = 0.22f), PynapliShape.CardShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Link,
                contentDescription = null,
                tint = PynapliColors.Accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "One subscription · two people",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Your paid tier extends to your active linked partner. Independent accounts always.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun String?.sourceLabel(): String =
    when (this) {
        EntitlementSource.OWN_SUBSCRIPTION -> "Paid subscription active"
        EntitlementSource.LINKED_PARTNER_BENEFIT -> "Linked partner benefit active"
        EntitlementSource.PROMO -> "Promo entitlement active"
        EntitlementSource.ADMIN -> "Admin entitlement active"
        else -> "Free plan active"
    }

private fun String.tierLabel(): String =
    when (this) {
        SubscriptionTier.FREE -> "Free"
        SubscriptionTier.SOCIAL -> "Social"
        SubscriptionTier.SELECT -> "Select"
        else -> this
    }

internal object StoreTestTags {
    const val LIST = "store-list"
}
