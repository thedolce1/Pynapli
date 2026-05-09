package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GridFiltersSheet(
    initial: GridFilters,
    onDismiss: () -> Unit,
    onApply: (GridFilters) -> Unit,
    onClearAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(initial) { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(GridFilterTags.SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SectionTitle("Distance")
            DistanceSection(
                radiusMeters = draft.radiusMeters,
                onChange = { meters -> draft = draft.copy(radiusMeters = meters) }
            )

            SectionTitle("Age")
            AgeSection(
                minimumAge = draft.minimumAge,
                maximumAge = draft.maximumAge,
                onChange = { lo, hi -> draft = draft.copy(minimumAge = lo, maximumAge = hi) }
            )

            SectionTitle("Presence")
            PresenceSection(
                online = draft.online,
                onToggle = { draft = draft.copy(online = !draft.online) }
            )

            SectionTitle("Who")
            WhoSection(
                singles = draft.singles,
                linked = draft.linked,
                partnered = draft.partnered,
                onTogglesChanged = { singles, linked, partnered ->
                    draft = draft.copy(singles = singles, linked = linked, partnered = partnered)
                }
            )

            SectionTitle("Trust")
            ComingSoonSection(message = "Verified, face photo verified, age verified — ships in F2.")

            SectionTitle("Intent")
            ComingSoonSection(message = "Solo, together, open to both, social, dating, events — ships in F3.")

            SectionTitle("Lifestyle")
            ComingSoonSection(message = "Soft swap, full swap, same-room, separate-room — age-gated, ships in F4.")

            SectionTitle("Logistics")
            ComingSoonSection(message = "Hosts, travels, hotel-friendly, public meet first — ships in F5.")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.testTag(GridFilterTags.CLEAR_ALL)
                ) { Text(text = "Clear all") }
                Box(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text(text = "Cancel") }
                androidx.compose.material3.Button(
                    onClick = { onApply(draft) },
                    modifier = Modifier.testTag(GridFilterTags.APPLY)
                ) { Text(text = "Apply") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun DistanceSection(radiusMeters: Int, onChange: (Int) -> Unit) {
    val km = (radiusMeters + 500) / 1_000
    Column {
        Text(
            text = "Within $km km",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = radiusMeters.toFloat(),
            onValueChange = { value -> onChange(value.toInt()) },
            valueRange = GridFilters.MIN_RADIUS_METERS.toFloat()..GridFilters.MAX_RADIUS_METERS.toFloat(),
            steps = 0
        )
    }
}

@Composable
private fun AgeSection(
    minimumAge: Int?,
    maximumAge: Int?,
    onChange: (Int?, Int?) -> Unit
) {
    val lo = (minimumAge ?: GridFilters.MIN_FILTER_AGE).coerceAtLeast(GridFilters.MIN_FILTER_AGE)
    val hi = (maximumAge ?: GridFilters.MAX_FILTER_AGE).coerceAtMost(GridFilters.MAX_FILTER_AGE)
    val isUnbounded = minimumAge == null && maximumAge == null
    Column {
        Text(
            text = if (isUnbounded) "Any age" else "$lo – $hi",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RangeSlider(
            value = lo.toFloat()..hi.toFloat(),
            onValueChange = { range ->
                val newLo = range.start.toInt()
                val newHi = range.endInclusive.toInt()
                if (newLo == GridFilters.MIN_FILTER_AGE && newHi == GridFilters.MAX_FILTER_AGE) {
                    onChange(null, null)
                } else {
                    onChange(newLo, newHi)
                }
            },
            valueRange = GridFilters.MIN_FILTER_AGE.toFloat()..GridFilters.MAX_FILTER_AGE.toFloat()
        )
    }
}

@Composable
private fun PresenceSection(online: Boolean, onToggle: () -> Unit) {
    SheetToggleChip(label = "Online now", active = online, onClick = onToggle)
}

@Composable
private fun WhoSection(
    singles: Boolean,
    linked: Boolean,
    partnered: Boolean,
    onTogglesChanged: (Boolean, Boolean, Boolean) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Singles", active = singles) {
            onTogglesChanged(!singles, linked, partnered)
        }
        SheetToggleChip(label = "Linked", active = linked) {
            onTogglesChanged(singles, !linked, partnered)
        }
        SheetToggleChip(label = "Partnered", active = partnered) {
            onTogglesChanged(singles, linked, !partnered)
        }
    }
}

@Composable
private fun SheetToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
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

@Composable
private fun ComingSoonSection(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
