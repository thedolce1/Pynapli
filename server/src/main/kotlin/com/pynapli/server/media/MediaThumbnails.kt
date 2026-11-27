package com.pynapli.server.media

import java.util.UUID

/**
 * Grid thumbnails (G2-2, GRID_IMPLEMENTATION_SPEC "Image Loading"): one server-made variant per
 * approved public profile photo.
 *
 * Size: longest side [MAX_EDGE_PX] = 480 px. A Grid tile is a ~120-145 dp square (3 columns on a
 * 360-430 dp phone), i.e. ~360-435 px at 3x, and it crops the photo to a square, so what matters is
 * the SHORT side after scaling. App uploads are mostly 3:4 portraits: at 480 the short side is 360 px
 * (a 3x tile on a 360 dp phone, ~2.5x on the widest), at 384 it would be 288 px, visibly soft on 3x
 * screens. 480 is the top of the 320-480 range, still ~1/5 of the 1080 px original's pixels.
 *
 * JPEG quality [JPEG_QUALITY] (0.80), EXIF stripped (orientation applied first), see
 * [ThumbnailRenderer]. Stored at [mediaThumbnailObjectKey] in the same bucket/provider as the
 * original, so the same signature scheme guards it.
 */
object MediaThumbnails {
    /** The only variant today. Bump the "v1" when the recipe changes, so caches never mix recipes. */
    const val VARIANT: String = "v1-thumb-480"
    const val MAX_EDGE_PX: Int = 480
    const val JPEG_QUALITY: Float = 0.80f
    const val CONTENT_TYPE: String = "image/jpeg"

    // Stable cache identity (G2-2 ticket 2). It names the BYTES, never grants access: the signed URL
    // next to it still does that, and a key alone fetches nothing.
    fun thumbnailCacheKey(mediaAssetId: UUID): String = "$mediaAssetId:$VARIANT"

    fun originalCacheKey(mediaAssetId: UUID): String = "$mediaAssetId:orig"
}

/** `media/{assetId}/v1-thumb-480.jpg`: next to the original at `media/{assetId}` (flat GCS namespace). */
internal fun mediaThumbnailObjectKey(mediaAssetId: UUID): String = "${mediaObjectKey(mediaAssetId)}/${MediaThumbnails.VARIANT}.jpg"
