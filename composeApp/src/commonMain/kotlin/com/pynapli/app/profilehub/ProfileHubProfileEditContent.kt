package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.grid.Intent
import com.pynapli.app.grid.LifestyleTag
import com.pynapli.app.grid.LogisticsTag
import com.pynapli.app.media.MediaAlbumCaps
import com.pynapli.app.media.MediaAlbumItemUi
import com.pynapli.shared.profile.ProfileTaxonomies
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
internal fun ProfileEditContent(
    state: ProfileHubUiState,
    onBack: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onBirthdateChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onNeighborhoodChanged: (String) -> Unit,
    onGenderChanged: (String) -> Unit,
    onGenderDetailChanged: (String) -> Unit,
    onOrientationChanged: (String) -> Unit,
    onOrientationDetailChanged: (String) -> Unit,
    onRelationshipContextChanged: (String) -> Unit,
    onDiscoveryStyleChanged: (String) -> Unit,
    onToggleLifestyleTag: (String) -> Unit,
    onToggleLogisticsTag: (String) -> Unit,
    onToggleIntent: (String) -> Unit,
    onSave: () -> Unit,
    onDismissError: () -> Unit,
    onManagePhotos: () -> Unit,
    profilePhotos: List<MediaAlbumItemUi>,
    modifier: Modifier = Modifier,
) {
    val ageVerified = state.me?.profile?.ageVerified == true

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        ProfileHubTopBar(
            title = "Edit profile",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.errorMessage?.let { message ->
                item {
                    ProfileHubMessageBand(
                        message = message,
                        isError = true,
                        onDismiss = onDismissError,
                    )
                }
            }

            item {
                ProfileHubSection("Photos") {
                    ProfilePhotosEditRow(
                        photos = profilePhotos,
                        onManagePhotos = onManagePhotos,
                    )
                }
            }

            item {
                ProfileHubSection("Basics") {
                    ProfileTextField(
                        value = state.draft.displayName,
                        onValueChange = onDisplayNameChanged,
                        label = "Display name",
                    )
                    BirthdateField(
                        value = state.draft.birthdate,
                        onValueChange = onBirthdateChanged,
                    )
                    ProfileTextField(
                        value = state.draft.relationshipContext,
                        onValueChange = onRelationshipContextChanged,
                        label = "Relationship context",
                    )
                }
            }

            item {
                ProfileHubSection("About you") {
                    ProfileTextField(
                        value = state.draft.bio,
                        onValueChange = onBioChanged,
                        label = "Bio",
                        supportingText = "${state.draft.bio.length} of ${ProfileTaxonomies.BIO_MAX_LENGTH}",
                        singleLine = false,
                        minLines = 3,
                    )
                    ProfileTextField(
                        value = state.draft.neighborhood,
                        onValueChange = onNeighborhoodChanged,
                        label = "Neighborhood",
                        supportingText = "Shown on your profile (e.g. Mission). Optional.",
                    )
                }
            }

            item {
                ProfileHubSection("Gender") {
                    KeyChipFlow(
                        options = ProfileTaxonomies.genderOptions,
                        selectedKey = state.draft.gender,
                        onSelected = onGenderChanged,
                    )
                    if (state.draft.gender == ProfileTaxonomies.SELF_DESCRIBE) {
                        ProfileTextField(
                            value = state.draft.genderDetail,
                            onValueChange = onGenderDetailChanged,
                            label = "Describe your gender",
                            supportingText = "Shown on your profile; not used by filters.",
                        )
                    }
                }
            }

            item {
                ProfileHubSection("Orientation") {
                    KeyChipFlow(
                        options = ProfileTaxonomies.orientationOptions,
                        selectedKey = state.draft.orientation,
                        onSelected = onOrientationChanged,
                    )
                    if (state.draft.orientation == ProfileTaxonomies.SELF_DESCRIBE) {
                        ProfileTextField(
                            value = state.draft.orientationDetail,
                            onValueChange = onOrientationDetailChanged,
                            label = "Describe your orientation",
                            supportingText = "Shown on your profile; not used by filters.",
                        )
                    }
                }
            }

            item {
                ProfileHubSection("Discovery style") {
                    discoveryStyleOptions.forEach { option ->
                        DiscoveryStyleRow(
                            label = option.label,
                            value = option.value,
                            selected = state.draft.discoveryStyle == option.value,
                            onSelected = onDiscoveryStyleChanged,
                        )
                    }
                }
            }

            item {
                ProfileHubSection("Intents") {
                    TagChipFlow(
                        options = Intent.entries.map { it.serverValue to it.label },
                        selected = state.draft.intents,
                        onToggle = onToggleIntent,
                    )
                }
            }

            item {
                ProfileHubSection("Lifestyle") {
                    if (ageVerified) {
                        TagChipFlow(
                            options = LifestyleTag.entries.map { it.serverValue to it.label },
                            selected = state.draft.lifestyleTags,
                            onToggle = onToggleLifestyleTag,
                        )
                    } else {
                        // PROFILE_SETTINGS_SPEC: high-sensitivity lifestyle terms are
                        // age-gated; the server rejects them for unverified accounts.
                        Text(
                            text = "Verify your age to add lifestyle tags.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            item {
                ProfileHubSection("Logistics") {
                    TagChipFlow(
                        options = LogisticsTag.entries.map { it.serverValue to it.label },
                        selected = state.draft.logisticsTags,
                        onToggle = onToggleLogisticsTag,
                    )
                }
            }
        }

        ProfileHubSaveButton(
            label = "Save profile",
            isSaving = state.isSaving,
            onSave = onSave,
        )
    }
}

@Composable
private fun KeyChipFlow(
    options: List<ProfileTaxonomies.Option>,
    selectedKey: String,
    onSelected: (String) -> Unit,
) {
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            PynapliPill(
                text = option.label,
                selected = option.key == selectedKey,
                onClick = { onSelected(option.key) },
            )
        }
    }
}

@Composable
private fun TagChipFlow(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            PynapliPill(
                text = label,
                selected = value in selected,
                onClick = { onToggle(value) },
            )
        }
    }
}

/**
 * Birthdate field backed by a Material date picker rather than a free-text "YYYY-MM-DD" entry:
 * fewer mistakes, and a familiar calendar. Stores the ISO value the server expects; renders a
 * friendly label. The whole field is a tap target that opens the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdateField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val parsed = remember(value) { value.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        OutlinedTextField(
            value = parsed?.let { formatBirthdate(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("Birthdate") },
            placeholder = { Text("Select your birthdate") },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
            colors =
                OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        // A disabled TextField does not take clicks, so an overlay opens the picker for the whole field.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clip(PynapliShape.SmallShape)
                    .clickable { showPicker = true },
        )
    }

    if (showPicker) {
        val initialMillis = parsed?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
                            onValueChange(date.toString())
                        }
                        showPicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Photos at the top of Edit profile so "your profile" is one flow. Shows the current profile
 * photos (Main first) and routes heavy editing — add, drag-to-reorder, set-as-main, delete — to
 * the album manager rather than duplicating it here.
 */
@Composable
private fun ProfilePhotosEditRow(
    photos: List<MediaAlbumItemUi>,
    onManagePhotos: () -> Unit,
) {
    val visible = photos.take(MediaAlbumCaps.PROFILE_PHOTO_CAP)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (visible.isEmpty()) {
            Text(
                text = "Add photos so people can see you.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visible.forEachIndexed { index, item ->
                    PhotoThumbnail(item = item, isPrimary = index == 0)
                }
            }
        }
        TextButton(
            onClick = onManagePhotos,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (visible.isEmpty()) "Add photos" else "Manage photos")
        }
    }
}

@Composable
private fun PhotoThumbnail(
    item: MediaAlbumItemUi,
    isPrimary: Boolean,
) {
    Box(
        modifier =
            Modifier
                .size(64.dp)
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, PynapliShape.CardShape),
    ) {
        if (item.isOwnerVisibleImage) {
            AsyncImage(
                model = item.mediaUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isPrimary) {
            Text(
                text = "Main",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .clip(PynapliShape.PillShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

private val MONTH_ABBREVIATIONS =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatBirthdate(date: LocalDate): String = "${MONTH_ABBREVIATIONS[date.month.ordinal]} ${date.day}, ${date.year}"
