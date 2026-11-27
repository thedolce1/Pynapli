package com.pynapli.server.media

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID

/** A photo as a client should load it: the (signed) URL, and the stable key that names its bytes. */
data class ResolvedMediaImage(
    val url: String,
    val cacheKey: String,
)

/**
 * Picks the URL and cache key for public profile photos on Grid tiles, partner minis, Activity rows
 * and as placeholders (G2-2 tickets 1-2). One cheap batch lookup per page tells which photos have a
 * ready thumbnail; everything else keeps the original exactly as before.
 *
 * The cache key names the bytes, not the person's right to see them: "<assetId>:v1-thumb-480" for the
 * thumbnail, "<assetId>:orig" for the original. A client caches by it, so a re-signed URL for the
 * same bytes is a cache hit. Access is still guarded by the signed URL: a key alone fetches nothing,
 * and the server only ever returns a key next to a URL it decided this viewer may have.
 *
 * Only call this with ids that came out of a public profile-photo projection (Grid/Swipe/profile/
 * Activity hydration). Private-album, chat and selfie media never go through here.
 */
class MediaImageResolver(
    private val urlResolver: MediaAssetUrlResolver,
    private val thumbnails: MediaThumbnailRepository?,
) {
    private val logger = LoggerFactory.getLogger(MediaImageResolver::class.java)

    suspend fun forAssets(ids: Collection<UUID?>): MediaImageSet {
        val distinct = ids.filterNotNullTo(HashSet())
        if (thumbnails == null || distinct.isEmpty()) return MediaImageSet(urlResolver, emptySet())
        val ready =
            try {
                thumbnails.readyAmong(distinct, MediaThumbnails.VARIANT)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Never fail a page over thumbnails: the originals still load.
                logger.warn("Thumbnail readiness lookup failed ({}); serving originals", error.javaClass.simpleName)
                emptySet()
            }
        return MediaImageSet(urlResolver, ready)
    }
}

class MediaImageSet(
    private val urlResolver: MediaAssetUrlResolver?,
    private val readyThumbnails: Set<UUID>,
) {
    /** Small surfaces (tile, mini, row): the thumbnail when ready, else the original. */
    fun tile(mediaAssetId: UUID?): ResolvedMediaImage? {
        val id = mediaAssetId ?: return null
        if (id in readyThumbnails) {
            urlResolver?.thumbnailContentUrl(id)?.let { return ResolvedMediaImage(it, MediaThumbnails.thumbnailCacheKey(id)) }
        }
        return original(id)
    }

    /** Full-size surfaces (profile pager, Swipe card): always the original. */
    fun original(mediaAssetId: UUID?): ResolvedMediaImage? {
        val id = mediaAssetId ?: return null
        val url = urlResolver?.publicContentUrl(id) ?: return null
        return ResolvedMediaImage(url, MediaThumbnails.originalCacheKey(id))
    }

    /**
     * The key a client may already hold the small version under (Grid tile), to paint while the
     * original loads. Null when no thumbnail is ready: then the tile used the original's key anyway.
     */
    fun thumbnailCacheKey(mediaAssetId: UUID?): String? = mediaAssetId?.takeIf { it in readyThumbnails }?.let(MediaThumbnails::thumbnailCacheKey)

    companion object {
        /** No thumbnails known (tests, a surface without a resolver): originals with their keys. */
        fun originalsOnly(urlResolver: MediaAssetUrlResolver?): MediaImageSet = MediaImageSet(urlResolver, emptySet())
    }
}

/** A surface built without a [MediaImageResolver] serves originals with stable keys, as before. */
suspend fun MediaImageResolver?.forAssetsOrOriginals(
    urlResolver: MediaAssetUrlResolver?,
    ids: Collection<UUID?>,
): MediaImageSet = this?.forAssets(ids) ?: MediaImageSet.originalsOnly(urlResolver)
