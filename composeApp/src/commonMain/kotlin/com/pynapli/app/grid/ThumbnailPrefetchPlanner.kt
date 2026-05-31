package com.pynapli.app.grid

internal const val GRID_THUMBNAIL_PREFETCH_WINDOW = 12

internal fun planThumbnailPrefetch(
    cards: List<GridProfileCardUi>,
    lastVisibleIndex: Int,
    windowSize: Int = GRID_THUMBNAIL_PREFETCH_WINDOW,
): List<String> {
    if (cards.isEmpty() || windowSize <= 0) return emptyList()
    val from = (lastVisibleIndex + 1).coerceAtLeast(0)
    if (from >= cards.size) return emptyList()
    val to = (from + windowSize).coerceAtMost(cards.size)
    val seen = LinkedHashSet<String>(to - from)
    for (index in from until to) {
        val url = cards[index].thumbnailUrl ?: continue
        seen += url
    }
    return seen.toList()
}
