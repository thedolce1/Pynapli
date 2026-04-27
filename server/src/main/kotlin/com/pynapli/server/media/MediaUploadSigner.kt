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
        contentType: String
    ): MediaUploadDestination
}

data class MediaUploadDestination(
    val storageProvider: String,
    val storageBucket: String,
    val storageObjectKey: String,
    val uploadUrl: String,
    val uploadMethod: String,
    val uploadHeaders: Map<String, String>,
    val expiresAt: Instant
)

class StaticMediaUploadSigner(
    private val storageProvider: String,
    private val storageBucket: String,
    private val uploadBaseUrl: String,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC()
) : MediaUploadSigner {
    override fun createUploadDestination(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        contentType: String
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
            expiresAt = clock.instant().plus(ttl)
        )
    }

}

class LocalDevMediaUploadSigner(
    private val storageBucket: String,
    private val uploadBaseUrl: String,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC()
) : MediaUploadSigner {
    override fun createUploadDestination(
        ownerUserId: UUID,
        mediaAssetId: UUID,
        albumKind: String,
        contentType: String
    ): MediaUploadDestination {
        val objectKey = "local/users/$ownerUserId/$albumKind/$mediaAssetId.${contentType.fileExtension()}"
        return MediaUploadDestination(
            storageProvider = "local",
            storageBucket = storageBucket,
            storageObjectKey = objectKey,
            uploadUrl = "${uploadBaseUrl.trimEnd('/')}/v1/media/uploads/local/$mediaAssetId",
            uploadMethod = "PUT",
            uploadHeaders = mapOf("Content-Type" to contentType),
            expiresAt = clock.instant().plus(ttl)
        )
    }
}

private fun String.fileExtension(): String = when (lowercase()) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "video/mp4" -> "mp4"
    "video/quicktime" -> "mov"
    else -> "bin"
}
