package com.pynapli.app.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.request.ImageRequest

/**
 * G2-2 stable image cache identity. Production photo URLs are short-lived signed URLs whose query
 * changes on every response (CDN `Expires`, GCS `X-Goog-Date`), so a URL-keyed cache re-downloads
 * the same photo on every Grid refresh. The server sends a stable key next to each URL
 * ("<assetId>:v1-thumb-480" or "<assetId>:orig"); this passes it to Coil as BOTH the memory cache key
 * and the disk cache key (Coil 3.4: an explicit `memoryCacheKey` is used as is, and the network
 * fetcher's disk key is `diskCacheKey ?: url`), so a re-signed URL for the same bytes is a cache hit.
 *
 * The key only names the bytes. Access is still guarded by the signed URL: a cache entry exists on
 * this device only because the server once gave this viewer a URL for it, and the key never fetches
 * anything by itself. Never build a key on the client; use the server's, or none.
 *
 * [placeholderCacheKey]: the key a smaller version may already sit under in memory (the Grid tile),
 * painted while this request loads (the profile pager's first paint).
 */
internal fun cachedImageModel(
    context: PlatformContext,
    url: String?,
    cacheKey: String?,
    placeholderCacheKey: String? = null,
    stats: ImageLoadStats? = null,
): Any? {
    if (url == null) return null
    if (cacheKey == null && placeholderCacheKey == null && stats == null) return url
    return ImageRequest
        .Builder(context)
        .data(url)
        .apply {
            if (cacheKey != null) {
                memoryCacheKey(cacheKey)
                diskCacheKey(cacheKey)
            }
            if (placeholderCacheKey != null) placeholderMemoryCacheKey(placeholderCacheKey)
            if (stats != null) {
                listener(
                    onSuccess = { _, result -> stats.record(result.dataSource) },
                    onError = { _, _ -> stats.recordError() },
                )
            }
        }.build()
}

@Composable
internal fun rememberCachedImageModel(
    url: String?,
    cacheKey: String?,
    placeholderCacheKey: String? = null,
    stats: ImageLoadStats? = null,
): Any? {
    val context = LocalPlatformContext.current
    return remember(context, url, cacheKey, placeholderCacheKey, stats) {
        cachedImageModel(context, url, cacheKey, placeholderCacheKey, stats)
    }
}

/**
 * Debug-only measurement (G2-2 ticket 7): how Grid tile images were served since the last report.
 * Counts only, never a URL, id or key. Off unless a debug shell sets [sink] (Android debug builds and
 * the dev desktop shell); with no sink every call is a no-op. Main-thread only (Coil calls listeners
 * on the main thread), so plain counters are enough.
 */
class ImageLoadStats internal constructor(
    private val label: String,
) {
    var sink: ((String) -> Unit)? = null

    private var memory = 0
    private var disk = 0
    private var network = 0
    private var errors = 0

    internal fun record(source: DataSource) {
        if (sink == null) return
        when (source) {
            DataSource.MEMORY_CACHE, DataSource.MEMORY -> memory++
            DataSource.DISK -> disk++
            DataSource.NETWORK -> network++
        }
    }

    internal fun recordError() {
        if (sink != null) errors++
    }

    /** Emits one line for the loads since the previous report (nothing when there were none). */
    fun report() {
        val out = sink ?: return
        if (memory + disk + network + errors == 0) return
        out("$label images: memory=$memory disk=$disk network=$network errors=$errors")
        memory = 0
        disk = 0
        network = 0
        errors = 0
    }

    companion object {
        /** Grid tiles (and their partner minis). */
        val grid: ImageLoadStats = ImageLoadStats("grid")
    }
}
