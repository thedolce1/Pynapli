package com.pynapli.app.tonight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
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
 * immediately) or together (proposed → partner approves). Photo attachment + moderation is a
 * later slice. Intent options follow the mode: solo can't pick "together" and vice-versa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartTonightSheet(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ProposeTonightSessionRequestDto) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(StartTonightTags.SHEET),
    ) {
        StartTonightSheetBody(isSubmitting = isSubmitting, onSubmit = onSubmit)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StartTonightSheetBody(
    isSubmitting: Boolean,
    onSubmit: (ProposeTonightSessionRequestDto) -> Unit,
) {
    var mode by remember { mutableStateOf(TonightMode.Solo) }
    var intent by remember { mutableStateOf(TonightIntent.Solo) }
    var hostMode by remember { mutableStateOf(TonightHostMode.PublicMeetFirst) }
    var durationHours by remember { mutableStateOf(2) }
    var note by remember { mutableStateOf("") }
    var mapVisible by remember { mutableStateOf(true) }

    // Intent options depend on the structural mode (server rejects mismatches).
    val intentOptions =
        if (mode == TonightMode.Solo) {
            listOf(TonightIntent.Solo, TonightIntent.Social, TonightIntent.Event)
        } else {
            listOf(TonightIntent.Together, TonightIntent.Social, TonightIntent.Event)
        }
    if (intent !in intentOptions) intent = intentOptions.first()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Start a session", color = PynapliColors.TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        SectionLabel("Mode")
        ChipRow {
            TonightMode.entries.forEach { m ->
                SelectableChip(label = m.label, selected = mode == m, tag = StartTonightTags.mode(m.server)) { mode = m }
            }
        }

        SectionLabel("Intent")
        ChipRow {
            intentOptions.forEach { i ->
                SelectableChip(label = intentDisplay(i), selected = intent == i, tag = StartTonightTags.intent(i.serverValue)) { intent = i }
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
    Text(text, color = PynapliColors.TextSecondary, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
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

private fun intentDisplay(intent: TonightIntent): String =
    when (intent) {
        TonightIntent.Solo -> "Solo"
        TonightIntent.Together -> "Together"
        TonightIntent.Social -> "Social"
        TonightIntent.Event -> "Events"
    }

internal object StartTonightTags {
    const val SHEET = "tonight-start-sheet"
    const val NOTE = "tonight-start-note"
    const val MAP_VISIBLE = "tonight-start-map-visible"
    const val SUBMIT = "tonight-start-submit"

    fun mode(value: String): String = "tonight-start-mode-$value"

    fun intent(value: String): String = "tonight-start-intent-$value"

    fun host(value: String): String = "tonight-start-host-$value"

    fun duration(hours: Int): String = "tonight-start-duration-$hours"
}
