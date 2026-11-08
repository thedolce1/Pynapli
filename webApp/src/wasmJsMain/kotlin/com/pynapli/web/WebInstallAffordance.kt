package com.pynapli.web

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape
import com.pynapli.web.platform.BrowserLocalStorage
import com.pynapli.web.platform.installPromptAvailable
import com.pynapli.web.platform.onWindowEvent
import com.pynapli.web.platform.showInstallPrompt

/**
 * Restrained "install the app" pill (WEB_PWA_GAMEPLAN.md W1.4), shown only when the
 * browser reports installability (`beforeinstallprompt`, captured pre-wasm in
 * index.html) and the visitor hasn't dismissed it. Dismissal persists per browser.
 */
@Composable
internal fun WebInstallAffordance(modifier: Modifier = Modifier) {
    var available by remember { mutableStateOf(installPromptAvailable()) }
    var dismissed by remember { mutableStateOf(BrowserLocalStorage.get(DISMISS_KEY) == "true") }

    DisposableEffect(Unit) {
        onWindowEvent("pynapli-install-ready") { available = true }
        onDispose { }
    }

    if (!available || dismissed) return

    Surface(
        modifier = modifier,
        shape = PynapliShape.PillShape,
        color = PynapliColors.SurfaceRaised,
        border = androidx.compose.foundation.BorderStroke(1.dp, PynapliColors.Border),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    showInstallPrompt()
                    available = false
                },
            ) {
                Text(text = "Install Pynapli", color = PynapliColors.Accent)
            }
            IconButton(
                onClick = {
                    dismissed = true
                    BrowserLocalStorage.set(DISMISS_KEY, "true")
                },
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss install suggestion",
                    tint = PynapliColors.TextSecondary,
                )
            }
        }
    }
}

private const val DISMISS_KEY = "pynapli.install.dismissed.v1"
