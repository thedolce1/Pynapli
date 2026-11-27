package com.pynapli.app.profilehub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliPasswordVisibilityToggle
import com.pynapli.app.design.handCursor
import com.pynapli.shared.auth.PasswordRules

/** Current password + new password (twice). Server errors (wrong current password) show inline. */
@Composable
internal fun ChangePasswordDialog(
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (current: String, new: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()

    val hint =
        when {
            new.isNotEmpty() && new.length < PasswordRules.MIN_LENGTH -> "Use at least ${PasswordRules.MIN_LENGTH} characters."
            new.isNotEmpty() && PasswordRules.isCommon(new) -> PasswordRules.TOO_COMMON_MESSAGE
            repeat.isNotEmpty() && repeat != new -> "The new passwords don't match."
            new.isNotEmpty() && new == current -> "Choose a password you aren't using now."
            else -> null
        }
    val canSave = !isSaving && current.isNotEmpty() && PasswordRules.problemWith(new) == null && repeat == new && new != current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("You'll stay signed in here. Any other phone or browser using your account will be signed out.")
                listOf(
                    Triple("Current password", current) { value: String -> current = value },
                    Triple("New password", new) { value: String -> new = value },
                    Triple("Repeat new password", repeat) { value: String -> repeat = value },
                ).forEach { (label, value, onChange) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        label = { Text(label) },
                        singleLine = true,
                        enabled = !isSaving,
                        visualTransformation = transformation,
                        trailingIcon = { PynapliPasswordVisibilityToggle(visible = visible) { visible = !visible } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                (hint ?: errorMessage)?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(current, new) },
                enabled = canSave,
                modifier = Modifier.handCursor(enabled = canSave),
            ) {
                Text(if (isSaving) "Saving" else "Change password")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
                modifier = Modifier.handCursor(enabled = !isSaving),
            ) {
                Text("Cancel")
            }
        },
    )
}
