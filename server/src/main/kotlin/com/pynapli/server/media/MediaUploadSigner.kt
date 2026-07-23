package com.pynapli.server.media

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface MediaUploadSigner {
    fun createUploadDestination(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        contentType: String,
    ): MediaUploadDestination
}

data class MediaUploadDestination(
    val storageProvider: String,
    val storageBucket: String,
    val storageObjectKey: String,
    val uploadUrl: String,
    val uploadMethod: String,
    val uploadHeaders: Map<String, String>,
    val expiresAt: Instant,
)

class StaticMediaUploadSigner(
    private val storageProvider: String,
    private val storageBucket: String,
    private val uploadBaseUrl: String,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC(),
) : MediaUploadSigner {
    override fun createUploadDestination(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        contentType: String,
    ): MediaUploadDestination {
        val objectKey = "users/$ownerUserId/$albumKind/$mediaAssetId.${contentType.fileExtension()}"
        val encodedObjectKey = objectKey.replace("/", "%2F")
        return MediaUploadDestination(
            storageProvider = storageProvider,
            storageBucket = storageBucket,
            storageObjectKey = objectKey,
            uploadUrl = "${uploadBaseUrl.trimEnd('/')}/$storageBucket/$encodedObjectKey",
            uploadMethod = "PUT",
            uploadHeaders = mapOf("Content-Type" to contentType),
            expiresAt = clock.instant().plus(ttl),
        )
    }
}

/**
 * Canonical GCS object key for an asset. Derivable from the asset id alone (no owner/album/ext in
 * the path) so the download resolver can sign a GET without a DB lookup. Access is gated by the V4
 * signature, not the path, and the per-asset row in `media_assets.storage_object_key` records this
 * same key. Content-Type is carried by object metadata set at upload time, so no extension is
 * needed for serving.
 */
internal fun mediaObjectKey(mediaAssetId: UUID): String = "media/$mediaAssetId"

/**
 * Production signer: issues GCS V4 signed `PUT` URLs via [GcsV4SignedUrlFactory] at [mediaObjectKey],
 * so the stored asset key and the signed URL agree. The client uploads with `PUT` + `Content-Type`.
 */
class GcsV4MediaUploadSigner(
    private val storageBucket: String,
    private val signedUrlFactory: GcsV4SignedUrlFactory,
    private val ttl: Duration = Duration.ofMinutes(15),
) : MediaUploadSigner {
    override fun createUploadDestination(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        contentType: String,
    ): MediaUploadDestination {
        val objectKey = mediaObjectKey(mediaAssetId)
        val signed =
            signedUrlFactory.sign(
                method = "PUT",
                bucket = storageBucket,
                objectKey = objectKey,
                ttl = ttl,
            )
        return MediaUploadDestination(
            storageProvider = "gcs",
            storageBucket = storageBucket,
            storageObjectKey = objectKey,
            uploadUrl = signed.url,
            uploadMethod = "PUT",
            uploadHeaders = mapOf("Content-Type" to contentType),
            expiresAt = Instant.ofEpochSecond(signed.expiresAtEpochSeconds),
        )
    }
}

/**
 * Local dev upload signer. Like [LocalMediaAssetUrlResolver], it prefers the per-request host
 * (set by `configureRequestMediaBaseUrl`) over the static [fallbackBaseUrl], so the PUT target is a
 * host the *client* can actually reach: the emulator gets `10.0.2.2`, a physical device on the LAN
 * gets the LAN IP, and a device behind the demo tunnel gets the tunnel origin. Without this, a
 * tester on a real phone received the emulator loopback URL and every photo upload failed while the
 * download URLs (already request-derived) looked fine.
 */
class LocalDevMediaUploadSigner(
    private val storageBucket: String,
    private val fallbackBaseUrl: String,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC(),
) : MediaUploadSigner {
    override fun createUploadDestination(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        contentType: String,
    ): MediaUploadDestination {
        val objectKey = "local/users/$ownerUserId/$albumKind/$mediaAssetId.${contentType.fileExtension()}"
        val base = (requestMediaBaseUrl.get() ?: fallbackBaseUrl).trimEnd('/')
        return MediaUploadDestination(
            storageProvider = "local",
            storageBucket = storageBucket,
            storageObjectKey = objectKey,
            uploadUrl = "$base/v1/media/uploads/local/$mediaAssetId",
            uploadMethod = "PUT",
            uploadHeaders = mapOf("Content-Type" to contentType),
            expiresAt = clock.instant().plus(ttl),
        )
    }
}

private fun String.fileExtension(): String =
    when (lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        else -> "bin"
    }
