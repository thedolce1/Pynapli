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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
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

@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onBirthdateChanged: (String) -> Unit,
    onAcceptedAgeAndTermsChanged: (Boolean) -> Unit,
    onModeSelected: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    onResetCodeChanged: (String) -> Unit = {},
    onResendResetCode: () -> Unit = {},
    onDevLogin: (() -> Unit)? = null,
    // When provided, "Create account" leaves this screen for the onboarding stepper
    // instead of switching to the legacy inline signup form.
    onStartCreateAccount: (() -> Unit)? = null,
    testerDiagnosticLabel: String? = null,
    modifier: Modifier = Modifier,
) {
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
            text =
                when (state.mode) {
                    AuthMode.SignIn -> "Sign in"
                    AuthMode.CreateAccount -> "Create account"
                    AuthMode.ResetPassword -> "Reset password"
                },
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

        if (state.mode == AuthMode.SignIn) {
            TextButton(
                onClick = { onModeSelected(AuthMode.ResetPassword) },
                enabled = !state.isSubmitting,
                modifier = Modifier.align(Alignment.End).handCursor(enabled = !state.isSubmitting),
            ) {
                Text("Forgot password?")
            }
        }

        if (state.mode == AuthMode.CreateAccount) {
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.birthdate,
                onValueChange = onBirthdateChanged,
                label = { Text("Birthdate") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = authTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = state.acceptedAgeAndTerms,
                    onCheckedChange = onAcceptedAgeAndTermsChanged,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.handCursor(enabled = !state.isSubmitting),
                )
                Text(
                    text = "I confirm I am 18 or older and accept the Terms and Privacy Policy.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(top = 12.dp),
                )
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
                        state.mode == AuthMode.SignIn -> "Sign in"
                        state.mode == AuthMode.CreateAccount -> "Create account"
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
            TextButton(
                onClick = {
                    if (resetting) {
                        onModeSelected(AuthMode.SignIn)
                    } else if (state.mode == AuthMode.SignIn && onStartCreateAccount != null) {
                        onStartCreateAccount()
                    } else {
                        onModeSelected(
                            if (state.mode == AuthMode.SignIn) AuthMode.CreateAccount else AuthMode.SignIn,
                        )
                    }
                },
                enabled = !state.isSubmitting,
                modifier = Modifier.handCursor(enabled = !state.isSubmitting),
            ) {
                Text(
                    when (state.mode) {
                        AuthMode.SignIn -> "Create account"
                        AuthMode.CreateAccount -> "Sign in"
                        AuthMode.ResetPassword -> "Back to sign in"
                    },
                )
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
