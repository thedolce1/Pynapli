package com.pynapli.app.design

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/*
 * Canonical decorative placeholder gradients — the neutral/tinted backdrops shown behind avatars
 * and cards before a photo loads. Previously each surface (Grid tiles, Swipe deck, Activity FOMO
 * grid, Profile hero) hardcoded its own palette and some were copy-pasted across files. This is the
 * single source so the same tone/seed maps to the same color everywhere with no drift.
 */
object PynapliPlaceholderGradients {
    /**
     * The 12 grid/avatar tone gradients. Order is load-bearing: it mirrors `GridCardTone` (indexed
     * by `ordinal`) and the first six are reused by the Activity FOMO grid tiles. Do not reorder.
     */
    val cardTones: List<Brush> =
        listOf(
            Brush.linearGradient(listOf(Color(0xFF2A2A2A), Color(0xFF7D5B3F))), // Warm
            Brush.linearGradient(listOf(Color(0xFF263238), Color(0xFF607D8B))), // Slate
            Brush.linearGradient(listOf(Color(0xFF1E1B1B), Color(0xFFB85C38))), // Rust
            Brush.linearGradient(listOf(Color(0xFF252525), Color(0xFF455A40))), // DeepGreen
            Brush.linearGradient(listOf(Color(0xFF2B2622), Color(0xFF9A6B38))), // Bronze
            Brush.linearGradient(listOf(Color(0xFF202124), Color(0xFF6B7280))), // Graphite
            Brush.linearGradient(listOf(Color(0xFF171717), Color(0xFF355C5B))), // Teal
            Brush.linearGradient(listOf(Color(0xFF2E2524), Color(0xFF884C5E))), // Rose
            Brush.linearGradient(listOf(Color(0xFF1C1B1A), Color(0xFF6D5D4F))), // Cocoa
            Brush.linearGradient(listOf(Color(0xFF1F2421), Color(0xFF526E48))), // Moss
            Brush.linearGradient(listOf(Color(0xFF212121), Color(0xFF7B6257))), // Stone
            Brush.linearGradient(listOf(Color(0xFF1A202C), Color(0xFF57606A))), // Midnight
        )

    /** The 5 swipe-deck card gradients (distinct, warmer palette tuned for the larger card). */
    val swipeDeckTones: List<Brush> =
        listOf(
            Brush.linearGradient(listOf(Color(0xFF2A2622), Color(0xFFB08A44))),
            Brush.linearGradient(listOf(Color(0xFF17211E), Color(0xFF486C5D))),
            Brush.linearGradient(listOf(Color(0xFF251D22), Color(0xFF854D62))),
            Brush.linearGradient(listOf(Color(0xFF191F24), Color(0xFF53616D))),
            Brush.linearGradient(listOf(Color(0xFF20201E), Color(0xFF72634F))),
        )

    /** Profile hero backdrop shown behind the photo pager before/under the first image. */
    val profileHero: Brush =
        Brush.linearGradient(listOf(Color(0xFF2B2622), Color(0xFF6D5D4F), Color(0xFF1C1B1A)))

    /**
     * Messaging avatar placeholder (inbox rows + the conversation header) shown before a thread
     * photo loads. One source so the inbox and the open conversation render the same tone — these
     * two surfaces previously copy-pasted the identical indigo→green pair.
     */
    val messagingAvatar: Brush =
        Brush.linearGradient(listOf(Color(0xFF202031), Color(0xFF193831)))

    /** Neutral locked/empty tile backdrop (e.g. gated Activity FOMO tiles). */
    val neutralTile: Brush =
        Brush.verticalGradient(listOf(Color(0xFF1A1A20), Color(0xFF26262E)))

    /** Deterministic palette index from a string seed (sum of char codes), matching prior logic. */
    fun stableIndex(
        seed: String,
        size: Int,
    ): Int = seed.fold(0) { total, char -> total + char.code }.mod(size)
}
