package com.pynapli.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.media.MediaAlbumsUiState
import kotlinx.datetime.LocalDate

/**
 * The DOB-first signup stepper (ONBOARDING_AUTH_SPEC). Pre-auth steps: DOB wheel ->
 * underage stop or account+legal. Post-auth steps (all skippable): location ->
 * display name -> photo -> grid. Driven entirely by [OnboardingStateHolder] state;
 * platform work (OS location prompt, photo picker) arrives via callbacks.
 */
@Composable
fun OnboardingFlowScreen(
    state: OnboardingUiState,
    locationState: LocationUiState,
    mediaState: MediaAlbumsUiState,
    today: LocalDate,
    onDobChanged: (LocalDate) -> Unit,
    onConfirmDob: () -> Unit,
    onBackToDob: () -> Unit,
    onCancelToSignIn: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onAcceptedTermsChanged: (Boolean) -> Unit,
    onAcceptedPrivacyChanged: (Boolean) -> Unit,
    onSubmitAccount: () -> Unit,
    onShareLocation: () -> Unit,
    onContinueFromLocation: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onSaveDisplayName: () -> Unit,
    onSkipDisplayName: () -> Unit,
    onPickPhoto: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pynapli",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (state.step != OnboardingStep.UnderageStop) {
            StepProgressDots(step = state.step)
            Spacer(modifier = Modifier.height(28.dp))
        } else {
            Spacer(modifier = Modifier.height(28.dp))
        }

        when (state.step) {
            OnboardingStep.Dob ->
                DobStep(
                    state = state,
                    today = today,
                    onDobChanged = onDobChanged,
                    onConfirmDob = onConfirmDob,
                    onCancelToSignIn = onCancelToSignIn,
                )

            OnboardingStep.UnderageStop ->
                UnderageStopStep(
                    onBackToDob = onBackToDob,
                    onCancelToSignIn = onCancelToSignIn,
                )

            OnboardingStep.Account ->
                AccountStep(
                    state = state,
                    onEmailChanged = onEmailChanged,
                    onPasswordChanged = onPasswordChanged,
                    onAcceptedTermsChanged = onAcceptedTermsChanged,
                    onAcceptedPrivacyChanged = onAcceptedPrivacyChanged,
                    onSubmitAccount = onSubmitAccount,
                    onBackToDob = onBackToDob,
                )

            OnboardingStep.Location ->
                LocationStep(
                    locationState = locationState,
                    onShareLocation = onShareLocation,
                    onContinue = onContinueFromLocation,
                )

            OnboardingStep.Name ->
                NameStep(
                    state = state,
                    onDisplayNameChanged = onDisplayNameChanged,
                    onSaveDisplayName = onSaveDisplayName,
                    onSkipDisplayName = onSkipDisplayName,
                )

            OnboardingStep.Photo ->
                PhotoStep(
                    mediaState = mediaState,
                    onPickPhoto = onPickPhoto,
                    onFinish = onFinish,
                )
        }
    }
}

@Composable
private fun DobStep(
    state: OnboardingUiState,
    today: LocalDate,
    onDobChanged: (LocalDate) -> Unit,
    onConfirmDob: () -> Unit,
    onCancelToSignIn: () -> Unit,
) {
    StepTitle(
        title = "When's your birthday?",
        subtitle = "Pynapli is for adults. Your birthday is never shown on your profile — only your age.",
    )
    Spacer(modifier = Modifier.height(24.dp))
    DobWheelPicker(
        value = state.dob,
        yearRange = (today.year - 100)..today.year,
        onValueChange = onDobChanged,
    )
    StepError(state.errorMessage)
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onConfirmDob,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
    }
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onCancelToSignIn) {
        Text("Already have an account? Sign in")
    }
}

@Composable
private fun UnderageStopStep(
    onBackToDob: () -> Unit,
    onCancelToSignIn: () -> Unit,
) {
    StepTitle(
        title = "Pynapli is 18+",
        subtitle =
            "You must be at least 18 years old to use Pynapli. " +
                "We can't create an account for the birthday you entered.",
    )
    Spacer(modifier = Modifier.height(28.dp))
    Button(
        onClick = onBackToDob,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Go back")
    }
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onCancelToSignIn) {
        Text("Back to sign in")
    }
}

@Composable
private fun AccountStep(
    state: OnboardingUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onAcceptedTermsChanged: (Boolean) -> Unit,
    onAcceptedPrivacyChanged: (Boolean) -> Unit,
    onSubmitAccount: () -> Unit,
    onBackToDob: () -> Unit,
) {
    StepTitle(
        title = "Create your account",
        subtitle = "Just an email and a strong password — everything else can wait.",
    )
    Spacer(modifier = Modifier.height(20.dp))
    OutlinedTextField(
        value = state.email,
        onValueChange = onEmailChanged,
        label = { Text("Email") },
        singleLine = true,
        enabled = !state.isSubmitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        colors = onboardingTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChanged,
        label = { Text("Password") },
        supportingText = { Text("At least ${OnboardingStateHolder.MIN_PASSWORD_LENGTH} characters") },
        singleLine = true,
        enabled = !state.isSubmitting,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = onboardingTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(16.dp))
    LegalAcceptanceRow(
        text = "I accept the Terms of Service.",
        checked = state.acceptedTerms,
        enabled = !state.isSubmitting,
        onCheckedChange = onAcceptedTermsChanged,
    )
    LegalAcceptanceRow(
        text = "I accept the Privacy Policy.",
        checked = state.acceptedPrivacy,
        enabled = !state.isSubmitting,
        onCheckedChange = onAcceptedPrivacyChanged,
    )
    StepError(state.errorMessage)
    Spacer(modifier = Modifier.height(20.dp))
    SubmittingButton(
        text = "Create account",
        isSubmitting = state.isSubmitting,
        onClick = onSubmitAccount,
    )
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onBackToDob, enabled = !state.isSubmitting) {
        Text("Back")
    }
}

@Composable
private fun LocationStep(
    locationState: LocationUiState,
    onShareLocation: () -> Unit,
    onContinue: () -> Unit,
) {
    // The grid is distance-ordered, so a successful location submit is the natural
    // "done" signal — advance without an extra tap. Skipping or a denied prompt
    // leaves the grid in its location-required empty state (spec edge case).
    LaunchedEffect(locationState.currentLocation) {
        if (locationState.currentLocation != null) {
            onContinue()
        }
    }
    StepTitle(
        title = "See who's nearby",
        subtitle =
            "The grid shows people closest to you first, so Pynapli needs your location " +
                "while you use the app. Your exact position is never shown to others.",
    )
    StepError(locationState.errorMessage)
    Spacer(modifier = Modifier.height(28.dp))
    SubmittingButton(
        text = "Share my location",
        isSubmitting = locationState.isUpdating,
        onClick = onShareLocation,
    )
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onContinue, enabled = !locationState.isUpdating) {
        Text("Skip for now")
    }
}

@Composable
private fun NameStep(
    state: OnboardingUiState,
    onDisplayNameChanged: (String) -> Unit,
    onSaveDisplayName: () -> Unit,
    onSkipDisplayName: () -> Unit,
) {
    StepTitle(
        title = "What should we call you?",
        subtitle = "Your display name is what people see on the grid. You can change it any time.",
    )
    Spacer(modifier = Modifier.height(20.dp))
    OutlinedTextField(
        value = state.displayName,
        onValueChange = onDisplayNameChanged,
        label = { Text("Display name") },
        singleLine = true,
        enabled = !state.isSubmitting,
        colors = onboardingTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(state.errorMessage)
    Spacer(modifier = Modifier.height(20.dp))
    SubmittingButton(
        text = "Continue",
        isSubmitting = state.isSubmitting,
        onClick = onSaveDisplayName,
    )
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onSkipDisplayName, enabled = !state.isSubmitting) {
        Text("Skip for now")
    }
}

@Composable
private fun PhotoStep(
    mediaState: MediaAlbumsUiState,
    onPickPhoto: () -> Unit,
    onFinish: () -> Unit,
) {
    val hasPhoto = mediaState.profileItems.isNotEmpty()
    StepTitle(
        title = "Add a photo",
        subtitle =
            if (hasPhoto) {
                "Photo added! It will appear on your profile once it's approved."
            } else {
                "Profiles with photos get seen more. One is enough to start — you can add more later."
            },
    )
    StepError(mediaState.errorMessage)
    Spacer(modifier = Modifier.height(28.dp))
    if (hasPhoto) {
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start browsing")
        }
    } else {
        SubmittingButton(
            text = "Choose a photo",
            isSubmitting = mediaState.isUploadingProfile,
            onClick = onPickPhoto,
        )
        Spacer(modifier = Modifier.height(10.dp))
        TextButton(onClick = onFinish, enabled = !mediaState.isUploadingProfile) {
            Text("Skip for now")
        }
    }
}

@Composable
private fun StepProgressDots(step: OnboardingStep) {
    val currentIndex = progressSteps.indexOf(step).coerceAtLeast(0)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        progressSteps.forEachIndexed { index, _ ->
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index <= currentIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
            )
        }
    }
}

@Composable
private fun StepTitle(
    title: String,
    subtitle: String,
) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = subtitle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StepError(message: String?) {
    if (message != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SubmittingButton(
    text: String,
    isSubmitting: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        } else {
            Text(text)
        }
    }
}

@Composable
private fun LegalAcceptanceRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Whole-row toggle with a null-callback Checkbox: one semantics node for
    // accessibility, and the full row is the tap target.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun onboardingTextFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = MaterialTheme.colorScheme.primary,
    )

private val progressSteps =
    listOf(
        OnboardingStep.Dob,
        OnboardingStep.Account,
        OnboardingStep.Location,
        OnboardingStep.Name,
        OnboardingStep.Photo,
    )
