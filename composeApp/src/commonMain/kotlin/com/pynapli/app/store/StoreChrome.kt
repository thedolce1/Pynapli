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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
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
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier

@Composable
internal fun StoreHeader(
    entitlement: SubscriptionEntitlementDto?,
    isRestoringPurchases: Boolean,
    restoreEnabled: Boolean,
    onRestorePurchases: () -> Unit,
) {
    PynapliScreenHeader(
        leading = { StoreIconBadge() },
        title = "Store",
        subtitle = entitlement?.source.sourceLabel(),
        actions = {
            OutlinedButton(
                onClick = onRestorePurchases,
                enabled = restoreEnabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isRestoringPurchases) "Restoring" else "Restore")
            }
        },
    )
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
                .clip(RoundedCornerShape(12.dp))
                .background(PynapliColors.SurfaceRaised)
                .border(
                    width = 1.dp,
                    color = PynapliColors.Border,
                    shape = RoundedCornerShape(12.dp),
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
                .clip(RoundedCornerShape(12.dp))
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
