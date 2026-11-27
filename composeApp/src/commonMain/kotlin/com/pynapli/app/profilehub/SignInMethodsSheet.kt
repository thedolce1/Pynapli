package com.pynapli.app.profilehub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.PynapliSheetTitle
import com.pynapli.app.design.handCursor
import com.pynapli.shared.auth.SocialProvider

/**
 * Settings > Sign-in methods: the password line, then each provider this platform offers, with
 * Add or Remove. Removing the last way in is refused by the server and explained here.
 */
@Composable
internal fun SignInMethodsSheet(
    state: SignInMethodsUiState,
    onDismiss: () -> Unit,
    onLink: (SocialProvider) -> Unit,
    onUnlink: (SocialProvider) -> Unit,
) {
    PynapliBottomSheet(onDismiss = onDismiss) {
        PynapliSheetColumn {
            PynapliSheetTitle("Sign-in methods")
            Text(
                text = "Add Google or Apple to sign in without your password. They're never used to find you or shown to anyone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.isLoading && !state.loaded) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                state.hasPassword?.let { hasPassword ->
                    MethodRow(
                        title = "Password",
                        detail = if (hasPassword) "Set" else "Not set",
                    )
                }
                // A provider is offered only once its official artwork is in (SocialButtonGuard); an
                // account that already has it still sees it and can remove it.
                val offered = state.offered()
                val providers = (offered + state.linked.map { it.provider }).distinct()
                providers.forEach { provider ->
                    val linked = state.linked.firstOrNull { it.provider == provider }
                    val busy = state.busyProvider == provider
                    val anyBusy = state.busyProvider != null
                    MethodRow(
                        title = provider.displayName,
                        detail = if (linked != null) linked.emailHint ?: "Added" else "Not added",
                    ) {
                        if (linked != null) {
                            TextButton(
                                onClick = { onUnlink(provider) },
                                enabled = !anyBusy,
                                modifier = Modifier.handCursor(enabled = !anyBusy),
                            ) {
                                Text(if (busy) "Removing" else "Remove")
                            }
                        } else if (provider in offered) {
                            TextButton(
                                onClick = { onLink(provider) },
                                enabled = !anyBusy,
                                modifier = Modifier.handCursor(enabled = !anyBusy),
                            ) {
                                Text(if (busy) "Adding" else "Add")
                            }
                        }
                    }
                }
            }
            (state.errorMessage ?: state.feedbackMessage)?.let { message ->
                Text(
                    text = message,
                    color = if (state.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MethodRow(
    title: String,
    detail: String,
    action: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        action()
    }
}
