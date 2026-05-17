package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.location.TravelCityPreset
import com.pynapli.app.location.TravelCityPresets
import com.pynapli.app.location.TravelLocationUiState

/**
 * Travel mode picker (SWIPE_IMPLEMENTATION_SPEC L339-354).
 *
 * MVP UX: pick from a small preset list (`TravelCityPresets.ALL`). When a
 * destination is active, the active chip shows selected and a "Clear travel
 * mode" button surfaces. Free/Social-tier users see a paywall hint instead of
 * the picker — the server returns `ENTITLEMENT_REQUIRED` and the sheet folds
 * the items list to the paywall copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TravelModeSheet(
    state: TravelLocationUiState,
    onDismiss: () -> Unit,
    onPickCity: (TravelCityPreset) -> Unit,
    onClear: () -> Unit,
    onOpenStore: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(TravelModeTags.SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Travel mode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Set a city and your deck shows people there. " +
                    "One location at a time. Clear it to go back to local.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.entitlementRequired) {
                TravelPaywallBanner(onSubscribe = onOpenStore)
            } else {
                state.current?.let { dto ->
                    Text(
                        text = "Currently in ${dto.city}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TravelCityPresets.ALL.forEach { preset ->
                        val active = state.current?.city?.equals(preset.city, ignoreCase = true) == true
                        AssistChip(
                            onClick = { onPickCity(preset) },
                            label = {
                                Text(
                                    text = preset.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = if (active) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.testTag(TravelModeTags.cityTag(preset.id))
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isActive && !state.entitlementRequired) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag(TravelModeTags.CLEAR)
                    ) { Text(text = "Clear travel mode") }
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text(text = "Close") }
            }
        }
    }
}

@Composable
private fun TravelPaywallBanner(onSubscribe: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(TravelModeTags.PAYWALL),
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
            text = "Travel mode is included with Pynapli Select.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Button(
            onClick = onSubscribe,
            modifier = Modifier.testTag(TravelModeTags.PAYWALL_SUBSCRIBE)
        ) { Text(text = "Subscribe", maxLines = 1) }
    }
}

internal object TravelModeTags {
    const val OPEN_SHEET: String = "travel-mode-open-sheet"
    const val SHEET: String = "travel-mode-sheet"
    const val CLEAR: String = "travel-mode-clear"
    const val PAYWALL: String = "travel-mode-paywall"
    const val PAYWALL_SUBSCRIBE: String = "travel-mode-paywall-subscribe"

    fun cityTag(id: String): String = "travel-mode-city-$id"
}
