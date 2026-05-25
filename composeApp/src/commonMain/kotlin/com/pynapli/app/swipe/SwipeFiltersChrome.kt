package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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

@Composable
internal fun SectionIntro(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
internal fun SectionHeader(
    title: String,
    dealbreakerKey: String,
    eligible: Boolean,
    locked: Boolean,
    onLockChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        DealbreakerLockChip(
            dealbreakerKey = dealbreakerKey,
            eligible = eligible,
            locked = locked,
            onClick = { onLockChange(!locked) }
        )
    }
}

@Composable
internal fun SubsectionHeader(
    title: String,
    dealbreakerKey: String,
    eligible: Boolean,
    locked: Boolean,
    onLockChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        DealbreakerLockChip(
            dealbreakerKey = dealbreakerKey,
            eligible = eligible,
            locked = locked,
            onClick = { onLockChange(!locked) }
        )
    }
}

/**
 * Inline "Lock" toggle per FILTERS_SPEC L354-371. Disabled when the underlying
 * filter is inactive so the user can't lock something that isn't set — the
 * spec's "clear and reversible" rule.
 *
 * When locked, this filter is sent in `SwipeDeckRequestDto.dealbreakers` and the
 * server treats it as a hard gate (skips it from B3 relaxation).
 */
@Composable
private fun DealbreakerLockChip(
    dealbreakerKey: String,
    eligible: Boolean,
    locked: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = locked,
        onClick = onClick,
        enabled = eligible,
        label = {
            Text(
                text = if (locked) "Locked" else "Lock",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        modifier = Modifier.testTag(SwipeFilterTags.dealbreakerTag(dealbreakerKey))
    )
}

@Composable
internal fun ComingSoonSection(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Pre-emptive paywall hint surfaced in-sheet when the draft selects any gated
 * advanced filter (FILTERS_SPEC L373-391, mirrors `requiresAdvancedEntitlement`).
 * Tapping Subscribe closes the sheet and hands off to the host's onOpenStore.
 * Server remains the entitlement authority — this banner just shortens the path
 * to the store for the user.
 */
@Composable
internal fun AdvancedPaywallBanner(onSubscribe: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(SwipeFilterTags.ADVANCED_PAYWALL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Advanced filters require Pynapli+.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onSubscribe,
            modifier = Modifier.testTag(SwipeFilterTags.ADVANCED_PAYWALL_SUBSCRIBE)
        ) {
            Text(text = "Subscribe", maxLines = 1)
        }
    }
}

@Composable
internal fun SwipeSheetChip(label: String, active: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            labelColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        )
    )
}

internal object SwipeFilterTags {
    const val SHEET = "swipe-filters-sheet"
    const val APPLY = "swipe-filters-sheet-apply"
    const val CLEAR_ALL = "swipe-filters-sheet-clear-all"
    const val OPEN_SHEET = "swipe-filters-open-sheet"
    const val ADVANCED_PAYWALL = "swipe-filters-advanced-paywall"
    const val ADVANCED_PAYWALL_SUBSCRIBE = "swipe-filters-advanced-paywall-subscribe"
    const val SAVED_PRESETS = "swipe-filters-saved-presets"
    const val SAVE_PRESET_NAME = "swipe-filters-save-preset-name"
    const val SAVE_PRESET_SUBMIT = "swipe-filters-save-preset-submit"

    fun dealbreakerTag(key: String): String = "swipe-filters-dealbreaker-$key"
}
