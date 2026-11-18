package com.pynapli.app.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * View haptics: no VIBRATE permission, and the system "Touch feedback" setting turns them off.
 * Newer constants are used where the API level has them, with the closest older one below.
 */
@Composable
internal actual fun rememberPlatformHaptics(): PynapliHaptics {
    val view = LocalView.current
    return remember(view) { AndroidViewHaptics(view) }
}

private class AndroidViewHaptics(
    private val view: View,
) : PynapliHaptics {
    override fun thresholdTick() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            },
        )
    }

    override fun commit() {
        view.performHapticFeedback(confirmConstant())
    }

    override fun matchSuccess() {
        // Two firm pulses, distinct from a single commit.
        view.performHapticFeedback(confirmConstant())
        view.postDelayed({ view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }, MATCH_SECOND_PULSE_DELAY_MS)
    }

    private fun confirmConstant(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
}

private const val MATCH_SECOND_PULSE_DELAY_MS = 140L
