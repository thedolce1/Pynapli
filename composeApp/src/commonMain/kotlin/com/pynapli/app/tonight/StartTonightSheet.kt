package com.pynapli.app.tonight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliSectionTitle
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.PynapliSheetTitle
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto

private const val MAX_NOTE = 100

private enum class TonightMode(
    val server: String,
    val label: String,
) {
    Solo("solo", "Solo"),
    Together("together", "Together"),
}

private enum class TonightHostMode(
    val server: String,
    val label: String,
) {
    PublicMeetFirst("public_meet_first", "Public meet first"),
    Hosting("hosting", "Hosting"),
    Traveling("traveling", "Traveling"),
}

/**
 * Start-session sheet (PT-5). Builds a [ProposeTonightSessionRequestDto] for solo (live
 * immediately) or together (proposed → partner approves). Intent options follow the mode: solo
 * can't pick "together" and vice-versa. An optional session photo can be picked from the viewer's
 * already-approved profile photos (so it shows immediately; the server feed gate still requires
 * approval, which profile photos already have).
 */
@Composable
fun StartTonightSheet(
    isSubmitting: Boolean,
    photoOptions: List<TonightPhotoOptionUi>,
    onDismiss: () -> Unit,
    onSubmit: (ProposeTonightSessionRequestDto) -> Unit,
) {
    PynapliBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(StartTonightTags.SHEET),
    ) {
        StartTonightSheetBody(isSubmitting = isSubmitting, photoOptions = photoOptions, onSubmit = onSubmit)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StartTonightSheetBody(
    isSubmitting: Boolean,
    photoOptions: List<TonightPhotoOptionUi>,
    onSubmit: (ProposeTonightSessionRequestDto) -> Unit,
) {
    var mode by remember { mutableStateOf(TonightMode.Solo) }
    var intent by remember { mutableStateOf(TonightIntent.Solo) }
    var hostMode by remember { mutableStateOf(TonightHostMode.PublicMeetFirst) }
    var durationHours by remember { mutableStateOf(2) }
    var note by remember { mutableStateOf("") }
    var mapVisible by remember { mutableStateOf(true) }
    var selectedPhotoId by remember { mutableStateOf<String?>(null) }

    // Intent options depend on the structural mode (server rejects mismatches).
    val intentOptions =
        if (mode == TonightMode.Solo) {
            listOf(TonightIntent.Solo, TonightIntent.Social, TonightIntent.Event)
        } else {
            listOf(TonightIntent.Together, TonightIntent.Social, TonightIntent.Event)
        }
    if (intent !in intentOptions) intent = intentOptions.first()

    PynapliSheetColumn {
        PynapliSheetTitle(text = "Start a session")

        SectionLabel("Mode")
        ChipRow {
            TonightMode.entries.forEach { m ->
                SelectableChip(label = m.label, selected = mode == m, tag = StartTonightTags.mode(m.server)) { mode = m }
            }
        }

        SectionLabel("Intent")
        ChipRow {
            intentOptions.forEach { i ->
                SelectableChip(label = i.label, selected = intent == i, tag = StartTonightTags.intent(i.serverValue)) { intent = i }
            }
        }

        SectionLabel("Hosting")
        ChipRow {
            TonightHostMode.entries.forEach { h ->
                SelectableChip(label = h.label, selected = hostMode == h, tag = StartTonightTags.host(h.server)) { hostMode = h }
            }
        }

        SectionLabel("Window")
        ChipRow {
            listOf(1, 2, 4).forEach { hrs ->
                SelectableChip(label = "${hrs}h", selected = durationHours == hrs, tag = StartTonightTags.duration(hrs)) { durationHours = hrs }
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { if (it.length <= MAX_NOTE) note = it },
            label = { Text("Note (optional)") },
            supportingText = { Text("${note.length}/$MAX_NOTE") },
            singleLine = false,
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().testTag(StartTonightTags.NOTE),
        )

        if (photoOptions.isNotEmpty()) {
            SectionLabel("Session photo (optional)")
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag(StartTonightTags.PHOTO_ROW),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                photoOptions.forEach { option ->
                    val selected = option.assetId == selectedPhotoId
                    Box(
                        modifier =
                            Modifier
                                .size(64.dp)
                                .clip(PynapliShape.CardShape)
                                .background(PynapliColors.SurfaceRaised)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) PynapliColors.Accent else PynapliColors.Border,
                                    shape = PynapliShape.CardShape,
                                ).clickable { selectedPhotoId = if (selected) null else option.assetId }
                                .testTag(StartTonightTags.photo(option.assetId)),
                    ) {
                        option.thumbnailUrl?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = "Session photo option",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(PynapliShape.CardShape),
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show on the zone map", color = PynapliColors.TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                Text("Always approximate — never your exact spot", color = PynapliColors.TextTertiary, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
            Switch(checked = mapVisible, onCheckedChange = { mapVisible = it }, modifier = Modifier.testTag(StartTonightTags.MAP_VISIBLE))
        }

        Button(
            onClick = {
                onSubmit(
                    ProposeTonightSessionRequestDto(
                        mode = mode.server,
                        intent = intent.serverValue,
                        hostMode = hostMode.server,
                        note = note.trim().ifEmpty { null },
                        durationHours = durationHours,
                        mapVisible = mapVisible,
                        photoMediaAssetId = selectedPhotoId,
                    ),
                )
            },
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth().testTag(StartTonightTags.SUBMIT),
        ) {
            Text(if (mode == TonightMode.Together) "Propose to partner" else "Go live")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    PynapliSectionTitle(text)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(),
        modifier = Modifier.testTag(tag),
    )
}

internal object StartTonightTags {
    const val SHEET = "tonight-start-sheet"
    const val NOTE = "tonight-start-note"
    const val MAP_VISIBLE = "tonight-start-map-visible"
    const val SUBMIT = "tonight-start-submit"
    const val PHOTO_ROW = "tonight-start-photo-row"

    fun photo(assetId: String): String = "tonight-start-photo-$assetId"

    fun mode(value: String): String = "tonight-start-mode-$value"

    fun intent(value: String): String = "tonight-start-intent-$value"

    fun host(value: String): String = "tonight-start-host-$value"

    fun duration(hours: Int): String = "tonight-start-duration-$hours"
}
