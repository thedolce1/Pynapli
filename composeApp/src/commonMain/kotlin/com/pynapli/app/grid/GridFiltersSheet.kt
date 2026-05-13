package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    savedState: SavedGridFiltersUiState = SavedGridFiltersUiState(),
    tonightState: TonightAvailabilityUiState = TonightAvailabilityUiState(),
    lifestyleFiltersAllowed: Boolean = true,
    onDismiss: () -> Unit,
    onApply: (GridFilters) -> Unit,
    onClearAll: () -> Unit,
    onSavePreset: (name: String, GridFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (com.pynapli.shared.network.dto.SavedGridFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {},
    onSetTonightAvailable: () -> Unit = {},
    onClearTonightAvailable: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialDraft = remember(initial, lifestyleFiltersAllowed) {
        initial.withLifestyleAccess(lifestyleFiltersAllowed)
    }
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    var saveName by remember { mutableStateOf("") }
    var glossaryVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(GridFilterTags.SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (savedState.items.isNotEmpty() || savedState.entitlementRequired) {
                SectionTitle("Saved")
                SavedFiltersSection(
                    state = savedState,
                    onApply = { dto ->
                        draft = GridFilters.fromGridQueryRequest(dto.payload)
                            .withLifestyleAccess(lifestyleFiltersAllowed)
                        onApplySavedPreset(dto)
                    },
                    onDelete = onDeleteSavedPreset
                )
            }

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
                tonightAvailable = draft.tonightAvailable,
                onOnlineToggle = { draft = draft.copy(online = !draft.online) },
                onTonightToggle = { draft = draft.copy(tonightAvailable = !draft.tonightAvailable) }
            )
            TonightAvailabilityRow(
                state = tonightState,
                onSet = onSetTonightAvailable,
                onClear = onClearTonightAvailable
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
            TrustSection(
                verified = draft.verified,
                facePhotoVerified = draft.facePhotoVerified,
                ageVerified = draft.ageVerified,
                onTogglesChanged = { v, fp, av ->
                    draft = draft.copy(verified = v, facePhotoVerified = fp, ageVerified = av)
                }
            )

            SectionTitle("Intent")
            IntentSection(
                selected = draft.discoveryStyles,
                onChange = { draft = draft.copy(discoveryStyles = it) }
            )
            IntentExtendedSection(
                selected = draft.intents,
                onChange = { draft = draft.copy(intents = it) }
            )

            if (lifestyleFiltersAllowed) {
                LifestyleSectionHeader(onOpenGlossary = { glossaryVisible = true })
                LifestyleSection(
                    selected = draft.lifestyleTags,
                    onChange = { draft = draft.copy(lifestyleTags = it) }
                )
            } else {
                SectionTitle("Lifestyle")
                ComingSoonSection(
                    message = "Verify your age in profile settings to access lifestyle filters."
                )
            }

            SectionTitle("Logistics")
            LogisticsSection(
                selected = draft.logisticsTags,
                onChange = { draft = draft.copy(logisticsTags = it) }
            )

            if (!savedState.entitlementRequired) {
                SectionTitle("Save current filters")
                SavePresetRow(
                    name = saveName,
                    onNameChange = { saveName = it },
                    isSaving = savedState.isSaving,
                    onSave = {
                        if (saveName.isNotBlank()) {
                            onSavePreset(saveName, draft.withLifestyleAccess(lifestyleFiltersAllowed))
                            saveName = ""
                        }
                    }
                )
            }

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
                    onClick = { onApply(draft.withLifestyleAccess(lifestyleFiltersAllowed)) },
                    modifier = Modifier.testTag(GridFilterTags.APPLY)
                ) { Text(text = "Apply") }
            }
        }
    }

    if (glossaryVisible) {
        LifestyleGlossaryDialog(onDismiss = { glossaryVisible = false })
    }
}

private fun GridFilters.withLifestyleAccess(allowed: Boolean): GridFilters =
    if (allowed) this else copy(lifestyleTags = emptySet())

@Composable
private fun LifestyleGlossaryDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "Close") }
        },
        title = { Text(text = "Lifestyle terms") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LIFESTYLE_GLOSSARY.forEach { (label, definition) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = definition,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

// Definitions sourced from LANGUAGE_AND_TAXONOMY_SPEC L274 onwards.
private val LIFESTYLE_GLOSSARY: List<Pair<String, String>> = listOf(
    "Soft swap" to "Open to some shared intimacy, with limits agreed beforehand.",
    "Full swap" to "Open to partner exchange when everyone involved consents.",
    "Same-room" to "Prefer experiences where partners stay in the same room.",
    "Separate-room" to "Open to partners being in separate spaces.",
    "New" to "Still learning or early in the lifestyle community.",
    "Experienced" to "Comfortable with lifestyle spaces, norms, and communication.",
    "Kink-friendly" to "Open to respectful conversations about kink, boundaries, and consent.",
    "Vanilla/social" to "Interested in conventional social connection or lower-intensity lifestyle interaction.",
    "ENM" to "Ethical non-monogamy; open to multiple connections with everyone's knowledge and consent.",
    "Swinging" to "A lifestyle where people, often couples, consensually socialize or connect with others outside their primary relationship."
)

@Composable
private fun SavedFiltersSection(
    state: SavedGridFiltersUiState,
    onApply: (com.pynapli.shared.network.dto.SavedGridFilterDto) -> Unit,
    onDelete: (id: String) -> Unit
) {
    if (state.entitlementRequired) {
        ComingSoonSection(message = "Saved filter presets unlock with Social or Select.")
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.items.forEach { dto ->
            SheetToggleChip(label = dto.name, active = false) { onApply(dto) }
        }
    }
}

@Composable
private fun SavePresetRow(
    name: String,
    onNameChange: (String) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(text = "Name this filter") }
        )
        androidx.compose.material3.Button(
            onClick = onSave,
            enabled = !isSaving && name.isNotBlank()
        ) { Text(text = if (isSaving) "Saving" else "Save") }
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
private fun PresenceSection(
    online: Boolean,
    tonightAvailable: Boolean,
    onOnlineToggle: () -> Unit,
    onTonightToggle: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Online now", active = online, onClick = onOnlineToggle)
        SheetToggleChip(label = "Available tonight", active = tonightAvailable, onClick = onTonightToggle)
    }
}

@Composable
private fun TonightAvailabilityRow(
    state: TonightAvailabilityUiState,
    onSet: () -> Unit,
    onClear: () -> Unit
) {
    val label = when {
        state.isMutating -> "Updating…"
        state.isCurrentlyAvailable -> "You're marked available tonight"
        else -> "Mark me available tonight (12h)"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (state.isCurrentlyAvailable) {
            TextButton(onClick = onClear, enabled = !state.isMutating) { Text(text = "Clear") }
        } else {
            TextButton(onClick = onSet, enabled = !state.isMutating) { Text(text = "Set") }
        }
    }
}

@Composable
private fun LogisticsSection(
    selected: Set<LogisticsTag>,
    onChange: (Set<LogisticsTag>) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LogisticsTag.entries.forEach { tag ->
            val active = tag in selected
            SheetToggleChip(label = tag.label, active = active) {
                onChange(if (active) selected - tag else selected + tag)
            }
        }
    }
}

@Composable
private fun IntentExtendedSection(
    selected: Set<Intent>,
    onChange: (Set<Intent>) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Intent.entries.forEach { intent ->
            val active = intent in selected
            SheetToggleChip(label = intent.label, active = active) {
                onChange(if (active) selected - intent else selected + intent)
            }
        }
    }
}

@Composable
private fun LifestyleSectionHeader(onOpenGlossary: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Lifestyle",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpenGlossary) { Text(text = "Glossary") }
    }
}

@Composable
private fun LifestyleSection(
    selected: Set<LifestyleTag>,
    onChange: (Set<LifestyleTag>) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LifestyleTag.entries.forEach { tag ->
            val active = tag in selected
            SheetToggleChip(label = tag.label, active = active) {
                onChange(if (active) selected - tag else selected + tag)
            }
        }
    }
}

@Composable
private fun IntentSection(
    selected: Set<DiscoveryStyle>,
    onChange: (Set<DiscoveryStyle>) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiscoveryStyle.entries.forEach { style ->
            val active = style in selected
            SheetToggleChip(label = style.label, active = active) {
                val next = if (active) selected - style else selected + style
                onChange(next)
            }
        }
    }
}

@Composable
private fun TrustSection(
    verified: Boolean,
    facePhotoVerified: Boolean,
    ageVerified: Boolean,
    onTogglesChanged: (Boolean, Boolean, Boolean) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Verified", active = verified) {
            onTogglesChanged(!verified, facePhotoVerified, ageVerified)
        }
        SheetToggleChip(label = "Face photo", active = facePhotoVerified) {
            onTogglesChanged(verified, !facePhotoVerified, ageVerified)
        }
        SheetToggleChip(label = "Age verified", active = ageVerified) {
            onTogglesChanged(verified, facePhotoVerified, !ageVerified)
        }
    }
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
