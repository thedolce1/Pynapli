package com.pynapli.app.onboarding

import androidx.compose.runtime.Composable

/**
 * Opens a policy page (Terms, Privacy) without losing the sign-up: on Android as a Custom Tab over
 * the app (the Custom Tabs intent protocol, no extra library); on the web in a new browser tab; on
 * desktop in the browser. iOS still hands the page to Safari through the platform URI handler (an
 * SFSafariViewController sheet is a follow-up that needs a Mac to verify). The stepper's state lives
 * in its holder, so coming back always lands on the same step.
 */
@Composable
internal expect fun rememberPolicyLinkOpener(): (String) -> Unit
