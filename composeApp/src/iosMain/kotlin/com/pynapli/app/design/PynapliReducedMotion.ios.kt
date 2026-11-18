package com.pynapli.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** Settings > Accessibility > Motion > Reduce Motion. Not verified on a device yet (no Mac in the loop). */
@Composable
internal actual fun rememberReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
