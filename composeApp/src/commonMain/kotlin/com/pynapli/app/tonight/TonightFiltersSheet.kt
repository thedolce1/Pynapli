package com.pynapli.app.tonight

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliSheetActionRow
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.handCursor
import com.pynapli.app.grid.BoundariesFilterSection
import com.pynapli.app.grid.LifestyleSection
import com.pynapli.app.grid.OrientationSection
import com.pynapli.app.grid.SectionTitle

/**
 * Playing Tonight filters: the same orientation, swap/room/experience and boundary choices as the
 * Grid sheet, applied to the session owner. Lifestyle choices need age verification (as on Grid).
 */
@Composable
internal fun TonightFiltersSheet(
    initial: TonightFeedFilters,
    lifestyleFiltersAllowed: Boolean,
    onDismiss: () -> Unit,
    onApply: (TonightFeedFilters) -> Unit,
) {
    var draft by remember(initial, lifestyleFiltersAllowed) { mutableStateOf(initial.withLifestyleAccess(lifestyleFiltersAllowed)) }

    PynapliBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(PlayingTonightTags.FILTERS_SHEET),
    ) {
        PynapliSheetColumn {
            SectionTitle("Orientation")
            OrientationSection(
                selected = draft.orientations,
                onChange = { draft = draft.copy(orientations = it) },
            )

            SectionTitle("Lifestyle")
            if (lifestyleFiltersAllowed) {
                LifestyleSection(
                    selected = draft.lifestyleTags,
                    onChange = { draft = draft.copy(lifestyleTags = it) },
                )
            } else {
                Text(
                    text = "Verify your age in profile settings to access lifestyle filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Boundaries")
            BoundariesFilterSection(
                selected = draft.boundaryItems,
                onChange = { draft = draft.copy(boundaryItems = it) },
            )

            PynapliSheetActionRow(
                start = {
                    TextButton(
                        onClick = { draft = TonightFeedFilters() },
                        modifier = Modifier.handCursor(),
                    ) { Text(text = "Clear all") }
                },
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.handCursor()) { Text(text = "Cancel") }
                Button(
                    onClick = { onApply(draft.withLifestyleAccess(lifestyleFiltersAllowed)) },
                    modifier = Modifier.testTag(PlayingTonightTags.FILTERS_APPLY).handCursor(),
                ) { Text(text = "Apply") }
            }
        }
    }
}
