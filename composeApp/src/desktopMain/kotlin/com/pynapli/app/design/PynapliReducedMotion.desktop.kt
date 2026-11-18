package com.pynapli.app.design

import androidx.compose.runtime.Composable

/** The dev-only desktop shell has no system setting to read. */
@Composable
internal actual fun rememberReducedMotion(): Boolean = false
