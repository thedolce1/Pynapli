package com.pynapli.app.swipe

import androidx.compose.ui.graphics.Brush
import com.pynapli.app.design.PynapliPlaceholderGradients

internal fun String.toBackgroundBrush(): Brush {
    val tones = PynapliPlaceholderGradients.swipeDeckTones
    return tones[stableIndex(tones.size)]
}

internal fun String.stableIndex(size: Int): Int = fold(0) { total, char -> total + char.code }.mod(size)
