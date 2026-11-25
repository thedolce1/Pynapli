package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.icons.filled.VerifiedUser
import com.pynapli.shared.network.dto.SubscriptionTier

/** The paywall row's button names the plan that unlocks what the viewer hit, never always "Social". */
internal fun paywallCtaLabel(upgradeTier: String?): String =
    when (upgradeTier) {
        SubscriptionTier.SELECT -> "Select"
        SubscriptionTier.SOCIAL -> "Social"
        else -> "See plans"
    }

@Composable
internal fun GridPaywallRow(
    message: String,
    onOpenStore: () -> Unit,
    // SubscriptionTier the row sells (from the server's result-limit tier or a paid-filter gate).
    upgradeTier: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenStore, modifier = Modifier.handCursor().testTag(GridPaywallTags.CTA)) {
            Text(text = paywallCtaLabel(upgradeTier), maxLines = 1)
        }
    }
}

/**
 * Section 3's boundary banner: one full-width row inside the Grid, right before the first locked
 * card. It names the plan that unlocks the rest (the server's unlockTier) and opens the plans sheet
 * on it. Same quiet surface as [GridPaywallRow], with the gold accent because it sells a plan.
 */
@Composable
internal fun GridLockedBanner(
    unlockTier: String?,
    onOpenStore: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(GridLockedTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = PynapliColors.Accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = lockedBannerMessage(unlockTier),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenStore, modifier = Modifier.handCursor().testTag(GridLockedTags.BANNER_CTA)) {
            Text(text = paywallCtaLabel(unlockTier), maxLines = 1, color = PynapliColors.Accent)
        }
    }
}

/**
 * Surfaced when the server rejects a gated grid action with AGE_VERIFICATION_REQUIRED.
 * Mirrors [GridPaywallRow] but routes the user into the Profile-Hub age-verification flow
 * (not the Store). Uses the premium gold accent on the CTA per the design north-star.
 */
@Composable
internal fun GridVerificationRow(
    message: String,
    onOpenAgeVerification: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag(GridVerificationTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = PynapliColors.Accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onOpenAgeVerification,
            modifier = Modifier.testTag(GridVerificationTags.CTA).handCursor(),
        ) {
            Text(text = "Verify", maxLines = 1, color = PynapliColors.Accent)
        }
    }
}

internal object GridPaywallTags {
    const val CTA: String = "grid-paywall-cta"
}

internal object GridLockedTags {
    const val BANNER: String = "grid-locked-banner"
    const val BANNER_CTA: String = "grid-locked-banner-cta"
}

internal object GridVerificationTags {
    const val ROW: String = "grid-verification-row"
    const val CTA: String = "grid-verification-cta"
}
