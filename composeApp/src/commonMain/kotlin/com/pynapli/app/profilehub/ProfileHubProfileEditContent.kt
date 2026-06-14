package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.grid.Intent
import com.pynapli.app.grid.LifestyleTag
import com.pynapli.app.grid.LogisticsTag
import com.pynapli.shared.profile.ProfileTaxonomies

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
                        onDismiss = { },
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
                    ProfileTextField(
                        value = state.draft.birthdate,
                        onValueChange = onBirthdateChanged,
                        label = "Birthdate",
                        supportingText = "YYYY-MM-DD",
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
