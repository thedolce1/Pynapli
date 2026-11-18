package com.pynapli.app.swipe

import com.pynapli.shared.network.dto.SwipeCardDto

internal const val SWIPE_IMAGE_PREFETCH_WINDOW = 6
internal const val SWIPE_TOP_CARD_PHOTO_PREFETCH = 5

// The top card and the one under it get their whole carousel, so tapping through photos on the
// next card is instant too; the rest of the window gets its cover only.
internal const val SWIPE_FULL_CAROUSEL_CARDS = 2

internal fun planSwipeImagePrefetch(
    cards: List<SwipeCardDto>,
    windowSize: Int = SWIPE_IMAGE_PREFETCH_WINDOW,
    topCardPhotoCount: Int = SWIPE_TOP_CARD_PHOTO_PREFETCH,
): List<String> {
    if (cards.isEmpty() || windowSize <= 0) return emptyList()
    val seen = LinkedHashSet<String>()
    cards.take(windowSize).forEachIndexed { index, card ->
        if (index < SWIPE_FULL_CAROUSEL_CARDS) {
            val carousel = card.photoUrls.ifEmpty { listOfNotNull(card.photoUrl) }
            carousel.take(topCardPhotoCount).forEach { seen += it }
        } else {
            val cover = card.photoUrls.firstOrNull() ?: card.photoUrl
            cover?.let { seen += it }
        }
        card.linkedPartner?.thumbnailUrl?.let { seen += it }
    }
    return seen.toList()
}
