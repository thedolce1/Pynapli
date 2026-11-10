package com.pynapli.app.design

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Esc closes this full-screen surface on a keyboard, the way a desktop user expects a page-sized
 * panel to close. Before this only the message composer answered Esc; Playing Tonight, Psync and
 * the profile screens ignored it (measured 2026-11-10).
 *
 * Same focus model as the Swipe stage's arrow keys: on Medium+ windows (where a keyboard is likely)
 * the surface takes focus when it appears, so Esc works without clicking first. Phones never request
 * focus, so touch behaviour is unchanged.
 *
 * Esc only closes while the surface ITSELF holds focus. Once someone clicks into a text field inside
 * it (a bio, a note), Esc stays with that field, so a stray Esc never throws away what they typed;
 * the back button still works. A dialog opened on top owns focus and handles Esc first.
 */
@Composable
fun Modifier.closeOnEscape(onClose: () -> Unit): Modifier {
    val wide = currentPynapliWindowSizeClass() != PynapliWindowSizeClass.Compact
    val focus = remember { FocusRequester() }
    var selfFocused by remember { mutableStateOf(false) }
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(wide) {
        if (wide) runCatching { focus.requestFocus() }
    }
    return this
        .focusRequester(focus)
        .onFocusChanged { selfFocused = it.isFocused }
        .focusable()
        .onKeyEvent { event ->
            if (event.key != Key.Escape || !selfFocused) return@onKeyEvent false
            if (event.type == KeyEventType.KeyDown) currentOnClose()
            true
        }
}
