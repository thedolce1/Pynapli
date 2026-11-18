package com.pynapli.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The few haptic moments the app uses (DESIGN_SPEC "important actions get haptic feedback where the
 * platform supports it"). Each platform maps them to its own feedback and honours its own system
 * setting: Android's View haptics follow "Touch feedback", iOS follows the system haptics switch,
 * the web vibrates only where `navigator.vibrate` exists, and the desktop dev shell does nothing.
 */
interface PynapliHaptics {
    /** A light tick: a drag crossed a decision threshold (in either direction). */
    fun thresholdTick()

    /** A firmer tap: a decision was committed (swipe released past the threshold, action button). */
    fun commit()

    /** A distinct success pattern: it's a match. */
    fun matchSuccess()
}

object NoOpPynapliHaptics : PynapliHaptics {
    override fun thresholdTick() = Unit

    override fun commit() = Unit

    override fun matchSuccess() = Unit
}

/**
 * Compose's own haptic feedback (used on iOS, where it drives the UIKit feedback generators). Kept in
 * common code so every target compiles it, not only the Mac-only iOS build.
 */
internal class ComposePynapliHaptics(
    private val feedback: HapticFeedback,
) : PynapliHaptics {
    override fun thresholdTick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    override fun commit() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    override fun matchSuccess() {
        feedback.performHapticFeedback(HapticFeedbackType.Confirm)
        feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/** Overrides the platform haptics (tests, previews). Null means "use the platform's". */
val LocalPynapliHaptics = staticCompositionLocalOf<PynapliHaptics?> { null }

@Composable
internal expect fun rememberPlatformHaptics(): PynapliHaptics

@Composable
fun rememberPynapliHaptics(): PynapliHaptics = LocalPynapliHaptics.current ?: rememberPlatformHaptics()
