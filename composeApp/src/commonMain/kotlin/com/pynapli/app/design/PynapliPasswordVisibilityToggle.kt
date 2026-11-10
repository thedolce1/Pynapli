package com.pynapli.app.design

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Visibility
import com.pynapli.app.design.icons.filled.VisibilityOff

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
