package com.pynapli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pynapli.app.design.PynapliTheme

/**
 * Minimal branded boot splash shown by the platform entry point while the DI graph is built off
 * the main thread (see androidApp `MainActivity`). Mirrors the in-app session-check splash
 * (App.kt) so the swap to the real UI is seamless. Lives in `:composeApp` because the Material3
 * surface is not exposed transitively to `:androidApp`.
 */
@Composable
fun PynapliBootSplash() {
    PynapliTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            Text(
                text = "Pynapli",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
