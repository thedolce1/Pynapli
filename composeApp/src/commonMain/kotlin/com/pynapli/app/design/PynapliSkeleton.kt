package com.pynapli.app.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp

/*
 * Skeleton/shimmer placeholders — content-shaped loading state that matches the eventual layout so
 * the screen "fills in" rather than flashing a bare spinner. One cheap infinite float drives a
 * translating highlight band over the surfaceVariant base: no relayout, a single animation clock,
 * and token-only colors (from the Material scheme), so it stays light on low-end devices. Share one
 * [rememberPynapliShimmerBrush] across a screenful of blocks so they sweep on the same clock.
 */

private const val ShimmerTravel = 1400f
private const val ShimmerBand = 420f
private const val ShimmerDurationMillis = 1200

/** Animated shimmer brush: a soft highlight band sweeping across the skeleton base color. */
@Composable
fun rememberPynapliShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = lerp(base, MaterialTheme.colorScheme.onSurfaceVariant, 0.10f)
    val transition = rememberInfiniteTransition(label = "pynapliShimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = ShimmerTravel,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = ShimmerDurationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "pynapliShimmerTranslate",
    )
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translate - ShimmerBand, 0f),
        end = Offset(translate, 0f),
    )
}

/**
 * A single shimmering placeholder block. Pass the [brush] from [rememberPynapliShimmerBrush] so a
 * group of blocks share one animation clock.
 */
@Composable
fun PynapliSkeletonBlock(
    brush: Brush,
    modifier: Modifier = Modifier,
    shape: Shape = PynapliShape.CardShape,
) {
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(brush),
    )
}
