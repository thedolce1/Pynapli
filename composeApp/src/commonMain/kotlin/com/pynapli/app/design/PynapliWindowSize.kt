package com.pynapli.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

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

/**
 * A full-bleed [background] with a centered content column capped at [maxWidth], so a
 * single-column surface reads as an intentional column on wide windows instead of a phone
 * layout stretched edge-to-edge. On phones the window is narrower than [maxWidth], so the cap
 * is a no-op — the Compact layout is unchanged. Surfaces swap this in for their outer
 * `Column(Modifier.fillMaxSize().background(...))` with no change to their content. See
 * [PynapliContentWidth] for the standard bounds.
 *
 * [columnModifier] is applied to the inner (capped) column *after* the width cap, so
 * scroll/inset/padding modifiers (`verticalScroll`, `statusBarsPadding`, `imePadding`, …) act
 * within the capped width — exactly where a form wants them.
 */
@Composable
fun PynapliMaxWidthColumn(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    columnModifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            // widthIn before fillMaxSize so the fill respects the cap: width = min(window, maxWidth).
            modifier = Modifier.widthIn(max = maxWidth).fillMaxSize().then(columnModifier),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}
