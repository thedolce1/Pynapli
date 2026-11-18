@file:OptIn(ExperimentalWasmJsInterop::class)

package com.pynapli.app.design

import androidx.compose.runtime.Composable
import kotlin.js.ExperimentalWasmJsInterop

/**
 * `navigator.vibrate` where the browser has it (Android Chrome); a no-op elsewhere (desktop
 * browsers, iOS Safari). Browsers only vibrate after a user gesture, which every caller here is.
 */
@Composable
internal actual fun rememberPlatformHaptics(): PynapliHaptics = BrowserVibrationHaptics

private object BrowserVibrationHaptics : PynapliHaptics {
    override fun thresholdTick() = vibrateOnce(THRESHOLD_TICK_MS)

    override fun commit() = vibrateOnce(COMMIT_MS)

    override fun matchSuccess() = vibrateMatchPattern()
}

private const val THRESHOLD_TICK_MS = 8
private const val COMMIT_MS = 18

// Kotlin/Wasm JS interop requires js() to be the whole body of a top-level function.
private fun vibrateOnce(durationMs: Int): Unit = js("{ try { if ('vibrate' in navigator) { navigator.vibrate(durationMs); } } catch (e) {} }")

private fun vibrateMatchPattern(): Unit = js("{ try { if ('vibrate' in navigator) { navigator.vibrate([30, 70, 45]); } } catch (e) {} }")
