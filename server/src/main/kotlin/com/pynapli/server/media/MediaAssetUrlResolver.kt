package com.pynapli.server.media

import java.time.Duration
import java.util.UUID

interface MediaAssetUrlResolver {
    fun publicContentUrl(mediaAssetId: UUID): String?
}

class LocalMediaAssetUrlResolver(
    private val uploadBaseUrl: String,
) : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String = "${uploadBaseUrl.trimEnd('/')}/v1/media/uploads/local/$mediaAssetId"
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
}
