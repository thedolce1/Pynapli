package com.pynapli.server.media

import java.time.Duration
import java.util.UUID

interface MediaAssetUrlResolver {
    fun publicContentUrl(mediaAssetId: UUID): String?

    /**
     * The grid thumbnail (MediaThumbnails) of an asset, signed the same way as [publicContentUrl].
     * Only [MediaImageSet] calls it, and only for assets whose thumbnail is ready and still eligible.
     * Null where a resolver has no thumbnail route.
     */
    fun thumbnailContentUrl(mediaAssetId: UUID): String? = null
}

class LocalMediaAssetUrlResolver(
    private val fallbackBaseUrl: String,
    // G2-2 ticket 7, local measurement only: append a per-second query so local URLs change on every
    // response like production's signed URLs do (PYNAPLI_MEDIA_LOCAL_URL_CHURN). The route ignores it.
    private val churnUrls: Boolean = false,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) : MediaAssetUrlResolver {
    private fun churn(url: String): String = if (churnUrls) "$url?v=${clock.instant().epochSecond}" else url

    // Prefer the per-request host (set by configureRequestMediaBaseUrl) so the emulator, a physical
    // device, and localhost each get a media URL they can actually reach; fall back to the static
    // configured base outside a request (background jobs / tests).
    override fun publicContentUrl(mediaAssetId: UUID): String {
        val base = (requestMediaBaseUrl.get() ?: fallbackBaseUrl).trimEnd('/')
        return churn("$base/v1/media/uploads/local/$mediaAssetId")
    }

    override fun thumbnailContentUrl(mediaAssetId: UUID): String {
        val base = (requestMediaBaseUrl.get() ?: fallbackBaseUrl).trimEnd('/')
        return churn("$base/v1/media/uploads/local/$mediaAssetId/thumb")
    }
}

/**
 * Production download resolver: returns a short-lived GCS V4 signed `GET` URL for an asset, derived
 * from the asset id (see [mediaObjectKey]). Callers already gate *who* gets a URL — e.g.
 * `MediaService.listAlbumItemsForViewer` enforces private-album grants before building DTOs — and
 * the short TTL bounds a leaked URL. The signature, not the object path, is the access gate.
 */
class GcsV4MediaAssetUrlResolver(
    private val storageBucket: String,
    private val signedUrlFactory: GcsV4SignedUrlFactory,
    private val ttl: Duration = Duration.ofMinutes(15),
) : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String =
        signedUrlFactory
            .sign(
                method = "GET",
                bucket = storageBucket,
                objectKey = mediaObjectKey(mediaAssetId),
                ttl = ttl,
            ).url

    override fun thumbnailContentUrl(mediaAssetId: UUID): String =
        signedUrlFactory
            .sign(
                method = "GET",
                bucket = storageBucket,
                objectKey = mediaThumbnailObjectKey(mediaAssetId),
                ttl = ttl,
            ).url
}

/**
 * Production download resolver that serves media through a **Cloud CDN**-backed bucket via a
 * short-lived Cloud CDN signed URL (see [CdnSignedUrlFactory]). The asset is reached at
 * `{cdnBaseUrl}/{mediaObjectKey}` so the edge caches it; the signature, not the path, is the access
 * gate, and callers still gate *who* receives a URL (e.g. private-album grant checks). Prefer this
 * over [GcsV4MediaAssetUrlResolver] when a CDN is provisioned, for cheaper, lower-latency delivery.
 */
class CdnMediaAssetUrlResolver(
    cdnBaseUrl: String,
    private val signedUrlFactory: CdnSignedUrlFactory,
    private val ttl: Duration = Duration.ofMinutes(15),
) : MediaAssetUrlResolver {
    private val normalizedBaseUrl = cdnBaseUrl.trimEnd('/')

    override fun publicContentUrl(mediaAssetId: UUID): String =
        signedUrlFactory
            .sign(
                baseUrl = "$normalizedBaseUrl/${mediaObjectKey(mediaAssetId)}",
                ttl = ttl,
            ).url

    override fun thumbnailContentUrl(mediaAssetId: UUID): String =
        signedUrlFactory
            .sign(
                baseUrl = "$normalizedBaseUrl/${mediaThumbnailObjectKey(mediaAssetId)}",
                ttl = ttl,
            ).url
}
