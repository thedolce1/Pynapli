package com.pynapli.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Compose's iOS haptic feedback, which drives the UIKit feedback generators and follows the system
 * haptics switch. Not verified on a device yet (no Mac in the loop).
 */
@Composable
internal actual fun rememberPlatformHaptics(): PynapliHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { ComposePynapliHaptics(feedback) }
}
