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
    val swipeImagePrefetchUrls = remember(cards) { planSwipeImagePrefetch(cards) }

    LaunchedEffect(swipeImagePrefetchUrls) {
        if (swipeImagePrefetchUrls.isEmpty()) return@LaunchedEffect
        val imageLoader = runCatching { SingletonImageLoader.get(platformContext) }.getOrNull() ?: return@LaunchedEffect
        swipeImagePrefetchUrls.forEach { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(platformContext)
                    .data(url)
                    .build()
            )
        }
    }
}
