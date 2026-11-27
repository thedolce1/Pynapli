package com.pynapli.app.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.pynapli.shared.network.dto.SwipeCardDto

@Composable
internal fun SwipeImagePrefetchEffect(cards: List<SwipeCardDto>) {
    val platformContext = LocalPlatformContext.current
    val swipeImagePrefetch = remember(cards) { planSwipeImagePrefetchImages(cards) }

    LaunchedEffect(swipeImagePrefetch) {
        if (swipeImagePrefetch.isEmpty()) return@LaunchedEffect
        val imageLoader = runCatching { SingletonImageLoader.get(platformContext) }.getOrNull() ?: return@LaunchedEffect
        swipeImagePrefetch.forEach { image ->
            // The card's own stable key (G2-2): the prefetch fills the entry the card reads.
            imageLoader.enqueue(
                ImageRequest
                    .Builder(platformContext)
                    .data(image.url)
                    .apply {
                        image.cacheKey?.let { key ->
                            memoryCacheKey(key)
                            diskCacheKey(key)
                        }
                    }.build(),
            )
        }
    }
}
