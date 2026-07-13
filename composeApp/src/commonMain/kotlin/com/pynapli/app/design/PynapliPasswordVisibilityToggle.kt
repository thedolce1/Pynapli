package com.pynapli.app.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

/**
 * Show/hide trailing icon for password fields. Use as `trailingIcon` on every password
 * OutlinedTextField (sign-in, signup stepper, future change-password) so the affordance
 * stays consistent; pair with `VisualTransformation.None` / `PasswordVisualTransformation()`
 * on the field itself.
 */
@Composable
fun PynapliPasswordVisibilityToggle(
    visible: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = if (visible) "Hide password" else "Show password",
        )
    }
}
