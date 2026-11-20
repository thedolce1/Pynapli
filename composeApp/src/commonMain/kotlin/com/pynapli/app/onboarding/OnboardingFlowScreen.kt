package com.pynapli.app.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliMaxWidthColumn
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.PynapliPasswordVisibilityToggle
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Person
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.media.MediaAlbumsUiState
import com.pynapli.shared.core.config.LegalLinks

/**
 * The DOB-first signup stepper (ONBOARDING_AUTH_SPEC), aligned to the founder's
 * Grindr reference capture (2026-06-10): typed DD/MM/YYYY birthday fields, a
 * dedicated policies step with read links and one Accept, then email+password, the
 * emailed 6-digit code ("Check your email"; the account only exists once it checks out),
 * location, and a single consolidated "Your profile" step (photo + display name).
 * Driven entirely by [OnboardingStateHolder] state; platform work (OS location
 * prompt, photo picker) arrives via callbacks.
 */
@Composable
fun OnboardingFlowScreen(
    state: OnboardingUiState,
    locationState: LocationUiState,
    mediaState: MediaAlbumsUiState,
    onDobDayChanged: (String) -> Unit,
    onDobMonthChanged: (String) -> Unit,
    onDobYearChanged: (String) -> Unit,
    onConfirmDob: () -> Unit,
    onBackToDob: () -> Unit,
    onCancelToSignIn: () -> Unit,
    onAcceptPolicies: () -> Unit,
    onBackToPolicies: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmitAccount: () -> Unit,
    onSignupCodeChanged: (String) -> Unit,
    onVerifySignupCode: () -> Unit,
    onResendSignupCode: () -> Unit,
    onUseDifferentEmail: () -> Unit,
    onShareLocation: () -> Unit,
    onContinueFromLocation: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onFinishProfile: () -> Unit,
    testerDiagnosticLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    PynapliMaxWidthColumn(
        maxWidth = PynapliContentWidth.Form,
        modifier = modifier,
        columnModifier =
            Modifier
                .statusBarsPadding()
                // Keep focused fields above the soft keyboard: the Profile step's
                // display-name field sits low enough to be covered otherwise.
                .imePadding()
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
        testerDiagnosticLabel?.let { label ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (state.step != OnboardingStep.UnderageStop) {
            StepProgressDots(step = state.step)
        }
        Spacer(modifier = Modifier.height(28.dp))

        when (state.step) {
            OnboardingStep.Dob ->
                DobStep(
                    state = state,
                    onDobDayChanged = onDobDayChanged,
                    onDobMonthChanged = onDobMonthChanged,
                    onDobYearChanged = onDobYearChanged,
                    onConfirmDob = onConfirmDob,
                    onCancelToSignIn = onCancelToSignIn,
                )

            OnboardingStep.UnderageStop ->
                UnderageStopStep(
                    onBackToDob = onBackToDob,
                    onCancelToSignIn = onCancelToSignIn,
                )

            OnboardingStep.Policies ->
                PoliciesStep(
                    onAcceptPolicies = onAcceptPolicies,
                    onBackToDob = onBackToDob,
                )

            OnboardingStep.Account ->
                AccountStep(
                    state = state,
                    onEmailChanged = onEmailChanged,
                    onPasswordChanged = onPasswordChanged,
                    onSubmitAccount = onSubmitAccount,
                    onBackToPolicies = onBackToPolicies,
                )

            OnboardingStep.VerifyEmail ->
                VerifyEmailStep(
                    state = state,
                    onSignupCodeChanged = onSignupCodeChanged,
                    onVerifySignupCode = onVerifySignupCode,
                    onResendSignupCode = onResendSignupCode,
                    onUseDifferentEmail = onUseDifferentEmail,
                )

            OnboardingStep.Location ->
                LocationStep(
                    locationState = locationState,
                    onShareLocation = onShareLocation,
                    onContinue = onContinueFromLocation,
                )

            OnboardingStep.Profile ->
                ProfileStep(
                    state = state,
                    mediaState = mediaState,
                    onDisplayNameChanged = onDisplayNameChanged,
                    onPickPhoto = onPickPhoto,
                    onFinishProfile = onFinishProfile,
                )
        }
    }
}

@Composable
private fun DobStep(
    state: OnboardingUiState,
    onDobDayChanged: (String) -> Unit,
    onDobMonthChanged: (String) -> Unit,
    onDobYearChanged: (String) -> Unit,
    onConfirmDob: () -> Unit,
    onCancelToSignIn: () -> Unit,
) {
    StepTitle(
        title = "Your birthday",
        subtitle = "You must be at least 18 years old to use Pynapli. Only your age shows on your profile.",
    )
    Spacer(modifier = Modifier.height(24.dp))
    val monthFocus = remember { FocusRequester() }
    val yearFocus = remember { FocusRequester() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DobField(
            value = state.dobDay,
            label = "DD",
            onValueChange = { value ->
                onDobDayChanged(value)
                if (value.count { it.isDigit() } >= 2) monthFocus.requestFocus()
            },
            modifier = Modifier.weight(1f),
        )
        DobField(
            value = state.dobMonth,
            label = "MM",
            onValueChange = { value ->
                onDobMonthChanged(value)
                if (value.count { it.isDigit() } >= 2) yearFocus.requestFocus()
            },
            modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(monthFocus),
        )
        DobField(
            value = state.dobYear,
            label = "YYYY",
            onValueChange = onDobYearChanged,
            modifier =
                Modifier
                    .weight(1.4f)
                    .focusRequester(yearFocus),
        )
    }
    StepError(state.errorMessage)
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onConfirmDob,
        enabled = state.dobComplete,
        modifier = Modifier.fillMaxWidth().handCursor(enabled = state.dobComplete),
    ) {
        Text("Next")
    }
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onCancelToSignIn, modifier = Modifier.handCursor()) {
        Text("Already have an account? Sign in")
    }
}

@Composable
private fun DobField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = onboardingTextFieldColors(),
        modifier = modifier,
    )
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
        modifier = Modifier.fillMaxWidth().handCursor(),
    ) {
        Text("Go back")
    }
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onCancelToSignIn, modifier = Modifier.handCursor()) {
        Text("Back to sign in")
    }
}

@Composable
private fun PoliciesStep(
    onAcceptPolicies: () -> Unit,
    onBackToDob: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    StepTitle(
        title = "Review our policies",
        subtitle = "To continue, you'll need to accept our Terms of Service and Privacy Policy.",
    )
    Spacer(modifier = Modifier.height(28.dp))
    PolicyReadRow(
        label = "Terms of Service",
        onRead = { uriHandler.openUri(LegalLinks.TERMS_OF_SERVICE) },
    )
    Spacer(modifier = Modifier.height(12.dp))
    PolicyReadRow(
        label = "Privacy Policy",
        onRead = { uriHandler.openUri(LegalLinks.PRIVACY_POLICY) },
    )
    Spacer(modifier = Modifier.height(28.dp))
    Text(
        text = "By tapping Accept, you agree to Pynapli's Terms of Service and Privacy Policy.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = onAcceptPolicies,
        modifier = Modifier.fillMaxWidth().handCursor(),
    ) {
        Text("Accept")
    }
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onBackToDob, modifier = Modifier.handCursor()) {
        Text("Back")
    }
}

@Composable
private fun PolicyReadRow(
    label: String,
    onRead: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onRead, modifier = Modifier.handCursor()) {
            Text("Read")
        }
    }
}

@Composable
private fun AccountStep(
    state: OnboardingUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmitAccount: () -> Unit,
    onBackToPolicies: () -> Unit,
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
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChanged,
        label = { Text("Password") },
        supportingText = { Text("At least ${OnboardingStateHolder.MIN_PASSWORD_LENGTH} characters") },
        singleLine = true,
        enabled = !state.isSubmitting,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            PynapliPasswordVisibilityToggle(visible = passwordVisible) { passwordVisible = !passwordVisible }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = onboardingTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(state.errorMessage)
    Spacer(modifier = Modifier.height(20.dp))
    SubmittingButton(
        text = "Create account",
        isSubmitting = state.isSubmitting,
        onClick = onSubmitAccount,
    )
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onBackToPolicies, enabled = !state.isSubmitting, modifier = Modifier.handCursor(enabled = !state.isSubmitting)) {
        Text("Back")
    }
}

/**
 * "Check your email". The copy is true whether or not the address already has an account (the
 * server sends a code to a new address and a sign-in note to an existing one, and answers the
 * same), so nothing on this screen tells anyone which it was. The code field is the same field
 * the reset-password screen uses: label, digits only, six long.
 */
@Composable
private fun VerifyEmailStep(
    state: OnboardingUiState,
    onSignupCodeChanged: (String) -> Unit,
    onVerifySignupCode: () -> Unit,
    onResendSignupCode: () -> Unit,
    onUseDifferentEmail: () -> Unit,
) {
    StepTitle(
        title = "Check your email",
        subtitle =
            "If ${state.verifyEmailLabel} can be used for a new account, we've sent a 6-digit code to it. " +
                "It works for 30 minutes. If that address already has an account, the email says how to sign in instead.",
    )
    Spacer(modifier = Modifier.height(20.dp))
    OutlinedTextField(
        value = state.signupCode,
        onValueChange = onSignupCodeChanged,
        label = { Text("6-digit code") },
        singleLine = true,
        enabled = !state.isSubmitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = onboardingTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    state.signupNotice?.let { notice ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = notice,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    StepError(state.errorMessage)
    Spacer(modifier = Modifier.height(20.dp))
    SubmittingButton(
        text = "Verify",
        isSubmitting = state.isSubmitting,
        onClick = onVerifySignupCode,
    )
    Spacer(modifier = Modifier.height(10.dp))
    TextButton(onClick = onResendSignupCode, enabled = !state.isSubmitting, modifier = Modifier.handCursor(enabled = !state.isSubmitting)) {
        Text("Send a new code")
    }
    TextButton(onClick = onUseDifferentEmail, enabled = !state.isSubmitting, modifier = Modifier.handCursor(enabled = !state.isSubmitting)) {
        Text("Use a different email")
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
    TextButton(onClick = onContinue, enabled = !locationState.isUpdating, modifier = Modifier.handCursor(enabled = !locationState.isUpdating)) {
        Text("Skip for now")
    }
}

@Composable
private fun ProfileStep(
    state: OnboardingUiState,
    mediaState: MediaAlbumsUiState,
    onDisplayNameChanged: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onFinishProfile: () -> Unit,
) {
    val hasPhoto = mediaState.profileItems.isNotEmpty()
    StepTitle(
        title = "Your profile",
        subtitle = "Add a photo and a display name — or skip and do it later.",
    )
    Spacer(modifier = Modifier.height(20.dp))
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(120.dp)
                .clip(PynapliShape.PhotoShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    if (hasPhoto) {
        Text(
            text = "Photo added! It will appear once it's approved.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    } else {
        SubmittingButton(
            text = "Add photo",
            isSubmitting = mediaState.isUploadingProfile,
            onClick = onPickPhoto,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = PynapliShape.CardShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Profiles with photos get seen more.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    StepError(mediaState.errorMessage)
    Spacer(modifier = Modifier.height(20.dp))
    OutlinedTextField(
        value = state.displayName,
        onValueChange = onDisplayNameChanged,
        label = { Text("Display name") },
        supportingText = {
            Text("${state.displayName.length}/${OnboardingStateHolder.DISPLAY_NAME_MAX_LENGTH}")
        },
        singleLine = true,
        enabled = !state.isSubmitting,
        colors = onboardingTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(state.errorMessage)
    Spacer(modifier = Modifier.height(20.dp))
    SubmittingButton(
        text = "Next",
        isSubmitting = state.isSubmitting || mediaState.isUploadingProfile,
        onClick = onFinishProfile,
    )
}

@Composable
private fun StepProgressDots(step: OnboardingStep) {
    val currentIndex = progressSteps.indexOf(step).coerceAtLeast(0)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        progressSteps.forEachIndexed { index, _ ->
            // Reached steps fill with the accent; the current step stretches into a short
            // pill so "you are here" reads at a glance. Both animate through the shared
            // motion tokens, giving the stepper the same restrained life as the core loop.
            val reached = index <= currentIndex
            val color by animateColorAsState(
                targetValue =
                    if (reached) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                animationSpec = PynapliMotion.standardTween(),
                label = "stepDotColor",
            )
            val width by animateDpAsState(
                targetValue = if (index == currentIndex) 22.dp else 8.dp,
                animationSpec = PynapliMotion.standardTween(),
                label = "stepDotWidth",
            )
            Box(
                modifier =
                    Modifier
                        .size(width = width, height = 8.dp)
                        .clip(PynapliShape.PillShape)
                        .background(color),
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
        modifier = Modifier.fillMaxWidth().handCursor(enabled = !isSubmitting),
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
        OnboardingStep.Policies,
        OnboardingStep.Account,
        OnboardingStep.VerifyEmail,
        OnboardingStep.Location,
        OnboardingStep.Profile,
    )
