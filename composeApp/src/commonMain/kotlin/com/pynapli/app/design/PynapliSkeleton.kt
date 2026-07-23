package com.pynapli.app.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/**
 * Content-shaped grid loading state: a screenful of shimmering tiles laid out like a real
 * [androidx.compose.foundation.lazy.grid.LazyVerticalGrid] (same columns / gap / tile aspect /
 * radius), so the load "fills in" and dissolves into the real cards.
 */
@Composable
fun PynapliGridSkeleton(
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    tileAspectRatio: Float = 1f,
    tileShape: Shape = PynapliShape.CardShape,
    gap: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val brush = rememberPynapliShimmerBrush()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(columns) {
                    PynapliSkeletonBlock(
                        brush = brush,
                        shape = tileShape,
                        modifier =
                            Modifier
                                .weight(1f)
                                .aspectRatio(tileAspectRatio),
                    )
                }
            }
        }
    }
}

/**
 * Content-shaped list loading state: shimmering rows shaped like an avatar + two text lines, for
 * inbox / feed first-loads (Messages, Activity).
 */
@Composable
fun PynapliListSkeleton(
    modifier: Modifier = Modifier,
    rowCount: Int = 8,
) {
    val brush = rememberPynapliShimmerBrush()
    Column(modifier = modifier.fillMaxSize()) {
        repeat(rowCount) {
            SkeletonListRow(brush = brush)
        }
    }
}

@Composable
private fun SkeletonListRow(brush: Brush) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PynapliSkeletonBlock(
            brush = brush,
            shape = CircleShape,
            modifier = Modifier.size(46.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PynapliSkeletonBlock(
                brush = brush,
                shape = PynapliShape.SmallShape,
                modifier =
                    Modifier
                        .fillMaxWidth(0.5f)
                        .height(14.dp),
            )
            PynapliSkeletonBlock(
                brush = brush,
                shape = PynapliShape.SmallShape,
                modifier =
                    Modifier
                        .fillMaxWidth(0.72f)
                        .height(12.dp),
            )
        }
    }
}
