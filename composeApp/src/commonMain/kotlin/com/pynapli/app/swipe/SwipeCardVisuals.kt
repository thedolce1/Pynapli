package com.pynapli.app.swipe

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal fun String.toBackgroundBrush(): Brush {
    val tones =
        listOf(
            Brush.linearGradient(listOf(Color(0xFF2A2622), Color(0xFFB08A44))),
            Brush.linearGradient(listOf(Color(0xFF17211E), Color(0xFF486C5D))),
            Brush.linearGradient(listOf(Color(0xFF251D22), Color(0xFF854D62))),
            Brush.linearGradient(listOf(Color(0xFF191F24), Color(0xFF53616D))),
            Brush.linearGradient(listOf(Color(0xFF20201E), Color(0xFF72634F))),
        )
    val index = stableIndex(tones.size)
    return tones[index]
}

internal fun String.stableIndex(size: Int): Int = fold(0) { total, char -> total + char.code }.mod(size)
