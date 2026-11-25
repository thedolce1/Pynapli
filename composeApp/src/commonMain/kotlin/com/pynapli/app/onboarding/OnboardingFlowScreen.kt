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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.auth.autofillContentType
import com.pynapli.app.auth.backspaceMovesTo
import com.pynapli.app.auth.rememberAutoFocusRequester
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliMaxWidthColumn
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.PynapliPasswordVisibilityToggle
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Person
import com.pynapli.app.location.LOCATION_SKIP_COST_COPY
import com.pynapli.app.location.LocationAccessHelp
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.media.MediaAlbumsUiState
import com.pynapli.app.media.primaryOwnerProfilePhotoUrl
import com.pynapli.shared.core.config.LegalLinks
import kotlinx.coroutines.delay

/**
 * The DOB-first signup stepper (ONBOARDING_AUTH_SPEC), aligned to the founder's
 * Grindr reference capture (2026-06-10): a day/month/year scroll wheel on phones
 * (typed DD/MM/YYYY fields on wider windows, and on request), a
 * dedicated policies step with read links and one Accept, then email+password, the
 * emailed 6-digit code ("Check your email"; the account only exists once it checks out),
 * location, and a single consolidated "Your profile" step (photo + display name).
 * Driven entirely by [OnboardingStateHolder] state; platform work (OS location
 * prompt, photo picker) arrives via callbacks.
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    onStartSignupAgain: () -> Unit,
    onShareLocation: () -> Unit,
    onContinueFromLocation: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onFinishProfile: () -> Unit,
    // System back: one step back (OnboardingStateHolder.stepBack), never out of the app mid-sign-up.
    onStepBack: () -> Unit = {},
    // The phone birthday wheel (OnboardingStateHolder.showBirthdayWheel / selectWheelDate / useTypedBirthday).
    onShowBirthdayWheel: () -> Unit = {},
    onWheelDateChange: (day: Int, month: Int, year: Int) -> Unit = { _, _, _ -> },
    onUseTypedBirthday: () -> Unit = {},
    // Seconds until "Send a new code" is offered again (OnboardingStateHolder.resendCooldownSecondsLeft).
    resendCooldownSecondsLeft: () -> Int = { 0 },
    testerDiagnosticLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    // B6. Always enabled while the stepper is up: a disabled handler would let back close the app.
    // The holder ignores it while a request is in flight and on the post-code steps. Deprecated in
    // CMP 1.11 but deliberately retained; see the note in AppOverlays.kt. The crop overlay composes
    // after this screen, so its own handler wins while it is open.
    BackHandler(onBack = onStepBack)
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
                    onShowBirthdayWheel = onShowBirthdayWheel,
                    onWheelDateChange = onWheelDateChange,
                    onUseTypedBirthday = onUseTypedBirthday,
                    onConfirmDob = onConfirmDob,
                    onCancelToSignIn = onCancelToSignIn,
                )

            OnboardingStep.UnderageStop ->
                UnderageStopStep(
                    // The stop is sticky (OnboardingStateHolder.confirmDob): "Go back" leaves the stepper.
                    onGoBack = onStepBack,
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
                    onStartSignupAgain = onStartSignupAgain,
                    onSignIn = onCancelToSignIn,
                    resendCooldownSecondsLeft = resendCooldownSecondsLeft,
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
    onShowBirthdayWheel: () -> Unit,
    onWheelDateChange: (day: Int, month: Int, year: Int) -> Unit,
    onUseTypedBirthday: () -> Unit,
    onConfirmDob: () -> Unit,
    onCancelToSignIn: () -> Unit,
) {
    StepTitle(
        title = "Your birthday",
        subtitle = "You must be at least 18 years old to use Pynapli. Only your age shows on your profile.",
    )
    Spacer(modifier = Modifier.height(24.dp))
    // Phones get the scroll wheels (the pattern the big apps use); wider windows (web, desktop,
    // tablet) keep the typed DD/MM/YYYY fields, which are also the screen-reader-friendly way in.
    val compact = currentPynapliWindowSizeClass() == PynapliWindowSizeClass.Compact
    var typed by rememberSaveable { mutableStateOf(false) }
    if (compact && !typed) {
        LaunchedEffect(Unit) { onShowBirthdayWheel() }
        val day = state.dobDay.toIntOrNull()
        val month = state.dobMonth.toIntOrNull()
        val year = state.dobYear.toIntOrNull()
        if (day != null && month != null && year != null && state.wheelFirstYear > 0) {
            BirthdayWheelPicker(
                day = day,
                month = month,
                year = year,
                years = state.wheelFirstYear..state.wheelLastYear,
                onDateChange = onWheelDateChange,
            )
        }
        // The hint keeps its space once a wheel moves (only the text goes), so nothing below it jumps.
        if (state.wheelFirstYear > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Scroll to your birthday.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (state.dobWheelUntouched) 1f else 0f)
                        .then(if (state.dobWheelUntouched) Modifier else Modifier.clearAndSetSemantics {}),
            )
        }
        TextButton(
            onClick = {
                typed = true
                onUseTypedBirthday()
            },
            modifier = Modifier.handCursor(),
        ) {
            Text(TYPE_BIRTHDAY_INSTEAD)
        }
    } else {
        TypedDobFields(
            state = state,
            onDobDayChanged = onDobDayChanged,
            onDobMonthChanged = onDobMonthChanged,
            onDobYearChanged = onDobYearChanged,
            onConfirmDob = onConfirmDob,
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

/** The label of the wheel's way out to the typed fields (QA scripts tap it by this text). */
internal const val TYPE_BIRTHDAY_INSTEAD = "Type your birthday instead"

@Composable
private fun TypedDobFields(
    state: OnboardingUiState,
    onDobDayChanged: (String) -> Unit,
    onDobMonthChanged: (String) -> Unit,
    onDobYearChanged: (String) -> Unit,
    onConfirmDob: () -> Unit,
) {
    val dayFocus = rememberAutoFocusRequester()
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
            imeAction = ImeAction.Next,
            onImeAction = { monthFocus.requestFocus() },
            modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(dayFocus)
                    .autofillContentType(ContentType.BirthDateDay),
        )
        DobField(
            value = state.dobMonth,
            label = "MM",
            onValueChange = { value ->
                onDobMonthChanged(value)
                if (value.count { it.isDigit() } >= 2) yearFocus.requestFocus()
            },
            imeAction = ImeAction.Next,
            onImeAction = { yearFocus.requestFocus() },
            modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(monthFocus)
                    .backspaceMovesTo(dayFocus) { state.dobMonth.isEmpty() }
                    .autofillContentType(ContentType.BirthDateMonth),
        )
        DobField(
            value = state.dobYear,
            label = "YYYY",
            onValueChange = onDobYearChanged,
            imeAction = ImeAction.Done,
            // Enter / Done on the last field is Next, once the date is complete.
            onImeAction = { if (state.dobComplete) onConfirmDob() },
            modifier =
                Modifier
                    .weight(1.4f)
                    .focusRequester(yearFocus)
                    .backspaceMovesTo(monthFocus) { state.dobYear.isEmpty() }
                    .autofillContentType(ContentType.BirthDateYear),
        )
    }
}

@Composable
private fun DobField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
        colors = onboardingTextFieldColors(),
        modifier = modifier,
    )
}

@Composable
private fun UnderageStopStep(
    onGoBack: () -> Unit,
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
        onClick = onGoBack,
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
    // A tab over the app where the platform has one (Android Custom Tab, a new browser tab on the
    // web), so reading a policy never drops the sign-up.
    val openPolicy = rememberPolicyLinkOpener()
    StepTitle(
        title = "Review our policies",
        subtitle = "To continue, you'll need to accept our Terms of Service and Privacy Policy.",
    )
    Spacer(modifier = Modifier.height(28.dp))
    PolicyReadRow(
        label = "Terms of Service",
        onRead = { openPolicy(LegalLinks.TERMS_OF_SERVICE) },
    )
    Spacer(modifier = Modifier.height(12.dp))
    PolicyReadRow(
        label = "Privacy Policy",
        onRead = { openPolicy(LegalLinks.PRIVACY_POLICY) },
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
    val emailFocus = rememberAutoFocusRequester()
    val passwordFocus = remember { FocusRequester() }
    OutlinedTextField(
        value = state.email,
        onValueChange = onEmailChanged,
        label = { Text("Email") },
        singleLine = true,
        enabled = !state.isSubmitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
        colors = onboardingTextFieldColors(),
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(emailFocus)
                // A new account: password managers offer to save the address and generate a password.
                .autofillContentType(ContentType.NewUsername + ContentType.EmailAddress),
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!state.isSubmitting) onSubmitAccount() }),
        colors = onboardingTextFieldColors(),
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus)
                .autofillContentType(ContentType.NewPassword),
    )
    state.accountNotice?.let { notice ->
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
    onStartSignupAgain: () -> Unit,
    onSignIn: () -> Unit,
    resendCooldownSecondsLeft: () -> Int,
) {
    StepTitle(
        title = "Check your email",
        subtitle =
            "If ${state.verifyEmailLabel} can be used for a new account, we've sent a 6-digit code to it. " +
                "It works for 30 minutes. If that address already has an account, the email says how to sign in instead.",
    )
    Spacer(modifier = Modifier.height(20.dp))
    val codeFocus = rememberAutoFocusRequester()
    // The holder submits the code on its own at six digits (OnboardingStateHolder.updateSignupCode);
    // Done / Enter submits a shorter one so its "6-digit code" error shows.
    OutlinedTextField(
        value = state.signupCode,
        onValueChange = onSignupCodeChanged,
        label = { Text("6-digit code") },
        singleLine = true,
        enabled = !state.isSubmitting && !state.signupAttemptsSpent,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!state.isSubmitting) onVerifySignupCode() }),
        colors = onboardingTextFieldColors(),
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(codeFocus)
                .autofillContentType(ContentType.SmsOtpCode),
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
    if (state.signupAttemptsSpent) {
        // Out of guesses for this sign-up: a new code for it could never work, so the way on is a
        // new sign-up for the same address.
        SubmittingButton(
            text = "Start again",
            isSubmitting = state.isSubmitting,
            onClick = onStartSignupAgain,
        )
    } else {
        SubmittingButton(
            text = "Verify",
            isSubmitting = state.isSubmitting,
            onClick = onVerifySignupCode,
        )
        Spacer(modifier = Modifier.height(10.dp))
        // Ticks once a second while the wait lasts; the same wait for every address.
        var secondsLeft by remember { mutableStateOf(resendCooldownSecondsLeft()) }
        LaunchedEffect(state.resendAvailableAtEpochMs) {
            secondsLeft = resendCooldownSecondsLeft()
            while (secondsLeft > 0) {
                delay(1_000)
                secondsLeft = resendCooldownSecondsLeft()
            }
        }
        val canResend = !state.isSubmitting && secondsLeft == 0
        TextButton(onClick = onResendSignupCode, enabled = canResend, modifier = Modifier.handCursor(enabled = canResend)) {
            Text(if (secondsLeft > 0) "Send a new code in ${formatCountdown(secondsLeft)}" else "Send a new code")
        }
        // Always there: if no email ever arrives, a new code can't help either; a new sign-up can.
        TextButton(onClick = onStartSignupAgain, enabled = !state.isSubmitting, modifier = Modifier.handCursor(enabled = !state.isSubmitting)) {
            Text("No email? Start again")
        }
    }
    TextButton(onClick = onUseDifferentEmail, enabled = !state.isSubmitting, modifier = Modifier.handCursor(enabled = !state.isSubmitting)) {
        Text("Use a different email")
    }
    // Shown to everyone, whatever the address: the email itself tells an existing member to sign in.
    TextButton(onClick = onSignIn, enabled = !state.isSubmitting, modifier = Modifier.handCursor(enabled = !state.isSubmitting)) {
        Text("Already have an account? Sign in")
    }
}

/** 0:27 style. */
private fun formatCountdown(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

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
    // Blocked for good or services off: the way to Settings (or where the browser keeps it).
    LocationAccessHelp(locationState)
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
    // What skipping costs, said plainly next to the choice (Section 1, Permissions).
    Text(
        text = LOCATION_SKIP_COST_COPY,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProfileStep(
    state: OnboardingUiState,
    mediaState: MediaAlbumsUiState,
    onDisplayNameChanged: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onFinishProfile: () -> Unit,
) {
    // Everything here is optional (Grindr parity): a blank name and no photo still finish, and the
    // grid shows either way (OnboardingStateHolder.finishProfile).
    val hasPhoto = mediaState.profileItems.isNotEmpty()
    // The owner's own copy of the chosen photo (visible to them while it is in review).
    val photoUrl = mediaState.primaryOwnerProfilePhotoUrl()
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
        if (photoUrl != null) {
            // Drawn over the placeholder, which stays visible while the image loads.
            AsyncImage(
                model = photoUrl,
                contentDescription = "Your profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions =
            KeyboardActions(onDone = { if (!state.isSubmitting && !mediaState.isUploadingProfile) onFinishProfile() }),
        colors = onboardingTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(state.errorMessage)
    Spacer(modifier = Modifier.height(20.dp))
    // The stepper's last button: it finishes, so it says so.
    SubmittingButton(
        text = "Done",
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
