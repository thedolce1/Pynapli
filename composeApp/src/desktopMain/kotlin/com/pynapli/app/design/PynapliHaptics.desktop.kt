package com.pynapli.app.design

import androidx.compose.runtime.Composable

/** The desktop dev shell has no haptics. */
@Composable
internal actual fun rememberPlatformHaptics(): PynapliHaptics = NoOpPynapliHaptics
