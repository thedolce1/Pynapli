package com.pynapli.app.swipe

import com.pynapli.shared.network.dto.SwipeCardDto

internal const val SWIPE_IMAGE_PREFETCH_WINDOW = 4

internal fun planSwipeImagePrefetch(
    cards: List<SwipeCardDto>,
    windowSize: Int = SWIPE_IMAGE_PREFETCH_WINDOW
): List<String> {
    if (cards.isEmpty() || windowSize <= 0) return emptyList()
    val seen = LinkedHashSet<String>()
    cards.take(windowSize).forEach { card ->
        card.photoUrl?.let { seen += it }
        card.linkedPartner?.thumbnailUrl?.let { seen += it }
    }
    return seen.toList()
}
