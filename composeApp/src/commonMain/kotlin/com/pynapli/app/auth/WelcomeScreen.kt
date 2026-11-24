package com.pynapli.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliContentWidth
import com.pynapli.app.design.PynapliMaxWidthColumn
import com.pynapli.app.design.handCursor

/** The one-line value statement on the welcome landing. Tasteful on purpose: it is the store screenshot. */
internal const val WELCOME_VALUE_LINE = "Where lifestyle couples and curious adults meet, nearby and on their own terms."

/**
 * The first screen a signed-out visitor sees: the value line, **Create account** (primary, opens
 * the onboarding stepper) and **Sign in** (secondary, opens the sign-in form). Nothing here takes
 * an email, so nothing here can say anything about one.
 *
 * [socialSignIn] is the slot reserved for "Continue with Google / Apple" (onboarding + first grid
 * plan, Section 5). It is empty until those exist, so the layout does not move when they arrive:
 * they go above Create account, the way the other apps stack them.
 */
@Composable
fun WelcomeScreen(
    onCreateAccount: (() -> Unit)?,
    onSignIn: () -> Unit,
    onDevLogin: (() -> Unit)? = null,
    // Only the dev login submits from here; its error (if any) shows under the buttons.
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
    testerDiagnosticLabel: String? = null,
    modifier: Modifier = Modifier,
    socialSignIn: @Composable ColumnScope.() -> Unit = {},
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
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = WELCOME_VALUE_LINE,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
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

        Spacer(modifier = Modifier.height(40.dp))

        socialSignIn()

        onCreateAccount?.let { create ->
            Button(
                onClick = create,
                modifier = Modifier.fillMaxWidth().handCursor(),
            ) {
                Text("Create account")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        OutlinedButton(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth().handCursor(),
        ) {
            Text("Sign in")
        }

        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        onDevLogin?.let { login ->
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = login, enabled = !isSubmitting, modifier = Modifier.handCursor(enabled = !isSubmitting)) {
                Text("Dev login")
            }
        }
    }
}
