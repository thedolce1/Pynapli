package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ProfileEditContent(
    state: ProfileHubUiState,
    onBack: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onBirthdateChanged: (String) -> Unit,
    onGenderChanged: (String) -> Unit,
    onOrientationChanged: (String) -> Unit,
    onRelationshipContextChanged: (String) -> Unit,
    onDiscoveryStyleChanged: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                        value = state.draft.gender,
                        onValueChange = onGenderChanged,
                        label = "Gender",
                    )
                    ProfileTextField(
                        value = state.draft.orientation,
                        onValueChange = onOrientationChanged,
                        label = "Orientation",
                    )
                    ProfileTextField(
                        value = state.draft.relationshipContext,
                        onValueChange = onRelationshipContextChanged,
                        label = "Relationship context",
                    )
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
        }

        ProfileHubSaveButton(
            label = "Save profile",
            isSaving = state.isSaving,
            onSave = onSave,
        )
    }
}
