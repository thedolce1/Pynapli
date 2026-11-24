package com.pynapli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.pynapli.app.design.PynapliTheme

/**
 * Minimal branded boot splash shown by the platform entry point while the DI graph is built off
 * the main thread (see androidApp `MainActivity`). Draws exactly [PynapliSplashContent], the same
 * composable App.kt shows while the session settles, so the swap between the two stages never
 * moves the wordmark. Lives in `:composeApp` because the Material3 surface is not exposed
 * transitively to `:androidApp`.
 */
@Composable
fun PynapliBootSplash() {
    PynapliTheme {
        PynapliSplashContent()
    }
}

/**
 * The one splash: the wordmark centred on the app background. Both boot stages (graph build and
 * session settle) draw this, inside whatever [PynapliTheme] is current, so there is no top-left to
 * centre jump between them.
 */
@Composable
internal fun PynapliSplashContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Pynapli",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
