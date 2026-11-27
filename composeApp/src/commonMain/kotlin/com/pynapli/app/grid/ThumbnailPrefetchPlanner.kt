package com.pynapli.app.grid

internal const val GRID_THUMBNAIL_PREFETCH_WINDOW = 12

/** One image to warm: its URL and the server's stable cache key (G2-2), so the prefetch and the tile share one cache entry. */
internal data class PrefetchImage(
    val url: String,
    val cacheKey: String?,
)

internal fun planThumbnailPrefetch(
    cards: List<GridProfileCardUi>,
    lastVisibleIndex: Int,
    windowSize: Int = GRID_THUMBNAIL_PREFETCH_WINDOW,
): List<String> = planThumbnailPrefetchImages(cards, lastVisibleIndex, windowSize).map { it.url }

/**
 * The tiles after the last visible one, deduplicated by what names their bytes (the cache key, or the
 * URL when there is none), so two signed URLs for the same photo are fetched once.
 */
internal fun planThumbnailPrefetchImages(
    cards: List<GridProfileCardUi>,
    lastVisibleIndex: Int,
    windowSize: Int = GRID_THUMBNAIL_PREFETCH_WINDOW,
): List<PrefetchImage> {
    if (cards.isEmpty() || windowSize <= 0) return emptyList()
    val from = (lastVisibleIndex + 1).coerceAtLeast(0)
    if (from >= cards.size) return emptyList()
    val to = (from + windowSize).coerceAtMost(cards.size)
    val seen = LinkedHashMap<String, PrefetchImage>(to - from)
    for (index in from until to) {
        // Locked cards are never prefetched: their photos load only if the viewer scrolls to them,
        // and nothing about a locked person is cached ahead on the device (Section 3).
        if (cards[index].isLockedCard) continue
        val url = cards[index].thumbnailUrl ?: continue
        val key = cards[index].thumbnailCacheKey
        seen.getOrPut(key ?: url) { PrefetchImage(url, key) }
    }
    return seen.values.toList()
}
