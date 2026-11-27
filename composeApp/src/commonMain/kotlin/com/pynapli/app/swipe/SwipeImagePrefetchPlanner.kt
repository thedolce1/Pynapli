package com.pynapli.app.swipe

import com.pynapli.app.grid.PrefetchImage
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
): List<String> = planSwipeImagePrefetchImages(cards, windowSize, topCardPhotoCount).map { it.url }

/**
 * The same plan with the server's stable cache keys (G2-2), so a prefetch fills exactly the cache
 * entry the card reads. Deduplicated by what names the bytes: the key, or the URL when there is none.
 */
internal fun planSwipeImagePrefetchImages(
    cards: List<SwipeCardDto>,
    windowSize: Int = SWIPE_IMAGE_PREFETCH_WINDOW,
    topCardPhotoCount: Int = SWIPE_TOP_CARD_PHOTO_PREFETCH,
): List<PrefetchImage> {
    if (cards.isEmpty() || windowSize <= 0) return emptyList()
    val seen = LinkedHashMap<String, PrefetchImage>()

    fun add(
        url: String?,
        key: String?,
    ) {
        if (url == null) return
        seen.getOrPut(key ?: url) { PrefetchImage(url, key) }
    }
    cards.take(windowSize).forEachIndexed { index, card ->
        val carousel = card.photoUrls.ifEmpty { listOfNotNull(card.photoUrl) }
        val keys = card.photoCacheKeys.takeIf { it.size == carousel.size }.orEmpty()
        val count = if (index < SWIPE_FULL_CAROUSEL_CARDS) topCardPhotoCount else 1
        carousel.take(count).forEachIndexed { photo, url -> add(url, keys.getOrNull(photo)) }
        card.linkedPartner?.let { add(it.thumbnailUrl, it.thumbnailCacheKey) }
    }
    return seen.values.toList()
}
