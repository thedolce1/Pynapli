package com.pynapli.app.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/*
 * Motion helpers built on the [PynapliMotion] tokens (durations/easing/stagger live in
 * PynapliDimens.kt). High-impact, restrained, cheap-for-low-end moments only.
 */

/**
 * Staggered first-load reveal: fades + lifts an item into place, with a per-[index] delay so a
 * list/grid "arrives" rather than popping in. Cheap and low-end-safe — it animates `graphicsLayer`
 * alpha/translation only (no relayout), and items composed after [visible] is already true (scrolled
 * in, or appended via pagination) start fully shown, so only the initial set ever animates.
 *
 * Drive [visible] from a `remember { mutableStateOf(false) }` flipped true in a `LaunchedEffect`.
 */
@Composable
fun Modifier.pynapliEntrance(
    visible: Boolean,
    index: Int,
): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec =
            PynapliMotion.standardTween(
                durationMillis = PynapliMotion.STANDARD,
                delayMillis = index.coerceIn(0, PynapliMotion.MaxStaggerItems) * PynapliMotion.StaggerStepMillis,
            ),
        label = "pynapliEntrance",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 10.dp.toPx()
    }
}
