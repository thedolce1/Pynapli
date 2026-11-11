package com.pynapli.server.psync

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.shared.network.dto.SwipeCardDto
import java.util.UUID

/*
 * A Psync deck is a snapshot taken once when the session starts, but in production its photo URLs
 * are short-lived signed links (15 minutes). A session can run for hours and be resumed the next
 * day, so the URLs stored in the snapshot go dead long before the deck does. Every deck read
 * therefore re-issues them.
 *
 * Every resolver (local, GCS V4, Cloud CDN) puts the media asset id as the last path segment, so
 * the id is recovered from the stored URL rather than widening the snapshot format.
 */

/** This card with freshly issued photo URLs. A URL whose asset id can't be recovered is kept as is. */
internal fun SwipeCardDto.withFreshPhotoUrls(resolver: MediaAssetUrlResolver?): SwipeCardDto {
    if (resolver == null) return this
    val freshUrls = photoUrls.map { it.reissuedBy(resolver) }
    return copy(
        photoUrl = photoUrl?.reissuedBy(resolver),
        photoUrls = freshUrls,
        linkedPartner = linkedPartner?.let { partner -> partner.copy(thumbnailUrl = partner.thumbnailUrl?.reissuedBy(resolver)) },
    )
}

private fun String.reissuedBy(resolver: MediaAssetUrlResolver): String {
    val assetId = mediaAssetIdOrNull() ?: return this
    return resolver.publicContentUrl(assetId) ?: this
}

/** The media asset id a resolver-issued URL points at: its last path segment, ignoring any query. */
internal fun String.mediaAssetIdOrNull(): UUID? {
    val lastSegment = substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
    return runCatching { UUID.fromString(lastSegment) }.getOrNull()
}
