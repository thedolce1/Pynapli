package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.shared.network.dto.SavedSwipeFilterDto

/**
 * Saved-preset chip row (FILTERS_SPEC L463-477). One AssistChip per saved preset;
 * tap to apply (the host closes the sheet and routes the payload through
 * `stateHolder.applyFilters`). Free-tier viewers see `ComingSoonSection` copy
 * instead — the entitlement gate is server-authoritative but the client respects
 * it for UX so a free-tier viewer never sees an empty "no presets" state.
 */
@Composable
internal fun SavedSwipePresetsSection(
    state: SavedSwipeFiltersUiState,
    onApply: (SavedSwipeFilterDto) -> Unit,
    @Suppress("UNUSED_PARAMETER") onDelete: (id: String) -> Unit,
    onOpenAgeVerification: () -> Unit = {},
) {
    if (state.ageVerificationRequired) {
        SwipeVerificationBanner(onVerify = onOpenAgeVerification)
        return
    }
    if (state.entitlementRequired) {
        ComingSoonSection(message = "Saved swipe filter presets unlock with Social or Select.")
        return
    }
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(SwipeFilterTags.SAVED_PRESETS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.items.forEach { dto ->
            SwipeSheetChip(label = dto.name, active = false) { onApply(dto) }
        }
    }
}

/**
 * Surfaced in the swipe filter sheet when the server rejects a saved-filter action with
 * AGE_VERIFICATION_REQUIRED (lifestyle presets require age verification). Routes into the
 * Profile-Hub age-verification flow. Gold accent per the design north-star.
 */
@Composable
internal fun SwipeVerificationBanner(onVerify: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(SwipeFilterTags.SAVED_VERIFICATION),
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
            text = "Verify your age to save lifestyle filter presets.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onVerify,
            modifier = Modifier.testTag(SwipeFilterTags.SAVED_VERIFICATION_VERIFY),
        ) { Text(text = "Verify", maxLines = 1) }
    }
}

@Composable
internal fun SaveSwipePresetRow(
    name: String,
    onNameChange: (String) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag(SwipeFilterTags.SAVE_PRESET_NAME),
            singleLine = true,
            placeholder = { Text(text = "Name this filter") },
        )
        Button(
            onClick = onSave,
            enabled = !isSaving && name.isNotBlank(),
            modifier = Modifier.testTag(SwipeFilterTags.SAVE_PRESET_SUBMIT),
        ) { Text(text = if (isSaving) "Saving" else "Save") }
    }
}
