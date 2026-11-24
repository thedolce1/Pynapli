package com.pynapli.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliMaxWidthColumn
import com.pynapli.app.design.PynapliPasswordVisibilityToggle
import com.pynapli.app.design.handCursor

/**
 * Signed-out surface: the welcome landing ([WelcomeScreen]) until the person picks Sign in, then
 * the sign-in form, or the reset-password form behind "Forgot password?". There is no
 * create-account form here: Create account always opens the onboarding stepper
 * ([onStartCreateAccount]), which has the birthday gate and the emailed code.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onModeSelected: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    onShowWelcome: () -> Unit = {},
    onResetCodeChanged: (String) -> Unit = {},
    onResendResetCode: () -> Unit = {},
    onAppealReasonChanged: (String) -> Unit = {},
    onSubmitAppeal: () -> Unit = {},
    onDevLogin: (() -> Unit)? = null,
    // Opens the onboarding stepper. Null hides Create account (no stepper in this shell).
    onStartCreateAccount: (() -> Unit)? = null,
    testerDiagnosticLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    if (state.showWelcome && state.mode == AuthMode.SignIn) {
        WelcomeScreen(
            onCreateAccount = onStartCreateAccount,
            onSignIn = { onModeSelected(AuthMode.SignIn) },
            onDevLogin = onDevLogin,
            isSubmitting = state.isSubmitting,
            errorMessage = state.errorMessage,
            testerDiagnosticLabel = testerDiagnosticLabel,
            modifier = modifier,
        )
        return
    }

    // System back: reset -> sign-in form -> welcome landing (the welcome itself lets back leave the
    // app as usual). Deprecated in CMP 1.11 but deliberately retained; see the note in AppOverlays.kt.
    BackHandler(enabled = !state.isSubmitting) {
        if (state.mode == AuthMode.ResetPassword) onModeSelected(AuthMode.SignIn) else onShowWelcome()
    }

    PynapliMaxWidthColumn(
        maxWidth = PynapliContentWidth.Form,
        modifier = modifier,
        columnModifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pynapli",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        val resetting = state.mode == AuthMode.ResetPassword
        Text(
            text = if (resetting) "Reset password" else "Sign in",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
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

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChanged,
            label = { Text("Email") },
            singleLine = true,
            enabled = !state.isSubmitting && !(resetting && state.resetCodeSent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = authTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (resetting) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = state.resetNotice ?: "Enter the email you signed up with and we'll send you a 6-digit code.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (resetting && state.resetCodeSent) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = state.resetCode,
                onValueChange = onResetCodeChanged,
                label = { Text("6-digit code") },
                singleLine = true,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = authTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!resetting || state.resetCodeSent) {
            Spacer(modifier = Modifier.height(12.dp))

            var passwordVisible by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChanged,
                label = { Text(if (resetting) "New password" else "Password") },
                singleLine = true,
                enabled = !state.isSubmitting,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    PynapliPasswordVisibilityToggle(visible = passwordVisible) { passwordVisible = !passwordVisible }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = authTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!resetting) {
            TextButton(
                onClick = { onModeSelected(AuthMode.ResetPassword) },
                enabled = !state.isSubmitting,
                modifier = Modifier.align(Alignment.End).handCursor(enabled = !state.isSubmitting),
            ) {
                Text("Forgot password?")
            }
        }

        state.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.accountSuspended && !resetting) {
            Spacer(modifier = Modifier.height(16.dp))
            if (state.appealSent) {
                Text(
                    text = "Your appeal is with our safety team. Try signing in again in a few days; nothing else is needed from you.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = state.appealReason,
                    onValueChange = onAppealReasonChanged,
                    label = { Text("Why should we take another look?") },
                    minLines = 3,
                    enabled = !state.isSubmitting,
                    colors = authTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSubmitAppeal,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth().handCursor(enabled = !state.isSubmitting),
                ) {
                    Text("Send appeal")
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Button(
            onClick = onSubmit,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().handCursor(enabled = !state.isSubmitting),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            } else {
                Text(
                    when {
                        !resetting -> "Sign in"
                        state.resetCodeSent -> "Set new password"
                        else -> "Send code"
                    },
                )
            }
        }

        if (resetting && state.resetCodeSent) {
            TextButton(
                onClick = onResendResetCode,
                enabled = !state.isSubmitting,
                modifier = Modifier.handCursor(enabled = !state.isSubmitting),
            ) {
                Text("Send a new code")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (resetting) {
                TextButton(
                    onClick = { onModeSelected(AuthMode.SignIn) },
                    enabled = !state.isSubmitting,
                    modifier = Modifier.handCursor(enabled = !state.isSubmitting),
                ) {
                    Text("Back to sign in")
                }
            } else {
                onStartCreateAccount?.let { create ->
                    TextButton(
                        onClick = create,
                        enabled = !state.isSubmitting,
                        modifier = Modifier.handCursor(enabled = !state.isSubmitting),
                    ) {
                        Text("New here? Create account")
                    }
                }
            }
        }

        onDevLogin?.let { login ->
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                onClick = login,
                enabled = !state.isSubmitting,
                modifier = Modifier.handCursor(enabled = !state.isSubmitting),
            ) {
                Text("Dev login")
            }
        }
    }
}

@Composable
private fun authTextFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = MaterialTheme.colorScheme.primary,
    )
