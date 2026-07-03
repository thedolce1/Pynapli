package com.pynapli.app.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Window width classes (Material 3 breakpoints) driving the adaptive shell
 * (WEB_PWA_GAMEPLAN.md "Window size classes"): Compact keeps the proven phone
 * layouts untouched; Medium+ swaps the bottom bar for a navigation rail; Expanded
 * additionally unlocks multi-pane surfaces (two-pane Messages, Grid side panel).
 * Width-only on purpose — every current adaptation decision is horizontal.
 */
enum class PynapliWindowSizeClass {
    Compact,
    Medium,
    Expanded,
}

fun pynapliWindowSizeClassForWidth(widthDp: Float): PynapliWindowSizeClass =
    when {
        widthDp < MEDIUM_MIN_WIDTH_DP -> PynapliWindowSizeClass.Compact
        widthDp < EXPANDED_MIN_WIDTH_DP -> PynapliWindowSizeClass.Medium
        else -> PynapliWindowSizeClass.Expanded
    }

@Composable
fun currentPynapliWindowSizeClass(): PynapliWindowSizeClass {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val widthDp = with(density) { containerSize.width.toDp() }
    return pynapliWindowSizeClassForWidth(widthDp.value)
}

private const val MEDIUM_MIN_WIDTH_DP = 600f
private const val EXPANDED_MIN_WIDTH_DP = 840f
