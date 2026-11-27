package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliSectionIntro
import com.pynapli.app.design.PynapliSectionTitle
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.wideChipHeight

@Composable
internal fun SectionIntro(text: String) {
    PynapliSectionIntro(text)
}

@Composable
internal fun SectionTitle(text: String) {
    PynapliSectionTitle(text)
}

@Composable
internal fun SectionHeader(
    title: String,
    dealbreakerKey: String,
    eligible: Boolean,
    locked: Boolean,
    onLockChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        DealbreakerLockChip(
            dealbreakerKey = dealbreakerKey,
            eligible = eligible,
            locked = locked,
            onClick = { onLockChange(!locked) },
        )
    }
}

@Composable
internal fun SubsectionHeader(
    title: String,
    dealbreakerKey: String,
    eligible: Boolean,
    locked: Boolean,
    onLockChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        DealbreakerLockChip(
            dealbreakerKey = dealbreakerKey,
            eligible = eligible,
            locked = locked,
            onClick = { onLockChange(!locked) },
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
    onClick: () -> Unit,
) {
    FilterChip(
        selected = locked,
        onClick = onClick,
        enabled = eligible,
        leadingIcon =
            if (locked) {
                {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = PynapliColors.Accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                null
            },
        label = {
            Text(
                text = if (locked) "Locked" else "Lock",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (locked) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        colors =
            FilterChipDefaults.filterChipColors(
                // Locked = a hard dealbreaker gate; surface it with the premium gold accent
                // (a tinted fill + gold label/icon) so it's unmistakable at a glance vs the
                // plain outlined "Lock" affordance. Design north-star: gold = highlight accent.
                selectedContainerColor = PynapliColors.Accent.copy(alpha = 0.20f),
                selectedLabelColor = PynapliColors.Accent,
                selectedLeadingIconColor = PynapliColors.Accent,
            ),
        modifier = Modifier.testTag(SwipeFilterTags.dealbreakerTag(dealbreakerKey)).wideChipHeight().handCursor(enabled = eligible),
    )
}

@Composable
internal fun ComingSoonSection(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SwipeSheetChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    PynapliPill(
        text = label,
        selected = active,
        onClick = onClick,
    )
}

internal object SwipeFilterTags {
    const val SHEET = "swipe-filters-sheet"
    const val APPLY = "swipe-filters-sheet-apply"
    const val CLEAR_ALL = "swipe-filters-sheet-clear-all"
    const val OPEN_SHEET = "swipe-filters-open-sheet"
    const val LIFESTYLE_AGE_GATE = "swipe-filters-lifestyle-age-gate"
    const val SAVED_PRESETS = "swipe-filters-saved-presets"
    const val SAVED_VERIFICATION = "swipe-filters-saved-verification"
    const val SAVED_VERIFICATION_VERIFY = "swipe-filters-saved-verification-verify"
    const val SAVE_PRESET_NAME = "swipe-filters-save-preset-name"
    const val SAVE_PRESET_SUBMIT = "swipe-filters-save-preset-submit"

    fun dealbreakerTag(key: String): String = "swipe-filters-dealbreaker-$key"
}
