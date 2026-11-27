package com.pynapli.app.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics

/*
 * Small pieces shared by the sign-in form and the sign-up stepper (onboarding + first grid plan,
 * Section 1, "Friction"). Enter is not handled here: a single-line field already turns Enter into
 * its IME action (Compose's text key mapping), so every form wires `imeAction` + `keyboardActions`
 * once and gets Next/Done on soft keyboards and Enter on hardware ones from the same callback.
 */

/**
 * Tells the platform autofill (Android Autofill, iOS AutoFill / password managers, Compose's web
 * and desktop mappings) what the field holds, through the Compose semantics `contentType`.
 */
fun Modifier.autofillContentType(type: ContentType): Modifier = semantics { contentType = type }

/** A [FocusRequester] that takes focus once when the field first appears (the step's first field). */
@Composable
fun rememberAutoFocusRequester(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        // Not attached yet only if the field was skipped this frame; then there is nothing to focus.
        runCatching { requester.requestFocus() }
    }
    return requester
}

/**
 * Backspace in an empty field moves focus to [previous] (the typed birthday's DD <- MM <- YYYY).
 * Hardware keyboards always send the key; soft keyboards send it for an empty field on most IMEs.
 */
fun Modifier.backspaceMovesTo(
    previous: FocusRequester,
    isEmpty: () -> Boolean,
): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && isEmpty()) {
            runCatching { previous.requestFocus() }
            true
        } else {
            false
        }
    }
