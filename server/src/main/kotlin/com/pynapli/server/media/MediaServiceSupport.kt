package com.pynapli.server.media

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.MEDIA_IMAGE_MAX_BYTES
import com.pynapli.shared.network.dto.MEDIA_VIDEO_MAX_BYTES
import java.util.UUID

internal const val MEDIA_PROFILE_ALBUM_PHOTO_CAP = 6
internal const val MEDIA_PRIVATE_ALBUM_PHOTO_CAP = 12

internal val MEDIA_ALBUM_KINDS = setOf("profile", "private")
internal val MEDIA_TYPES = setOf("image", "video")
internal val MEDIA_CONTENT_TYPES =
    mapOf(
        "image" to setOf("image/jpeg", "image/png", "image/webp"),
        "video" to setOf("video/mp4", "video/quicktime"),
    )

class MediaException(
    val code: String,
) : RuntimeException(code)

internal suspend fun RateLimitService?.enforceMediaUploadRateLimit(ownerUserId: UUID) {
    if (this?.check(ownerUserId, RateLimitedAction.MEDIA_UPLOAD)?.allowed == false) {
        throw MediaException("RATE_LIMITED")
    }
}

internal fun String.toMediaUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw MediaException("INVALID_ID") }

internal fun String.maxMediaBytes(): Long =
    when (this) {
        "image" -> MEDIA_IMAGE_MAX_BYTES
        "video" -> MEDIA_VIDEO_MAX_BYTES
        else -> 0
    }

internal fun String.defaultMediaAlbumTitle(): String =
    when (this) {
        "profile" -> "Profile photos"
        "private" -> "Private album"
        else -> "Media"
    }

internal fun String.mediaAlbumPhotoCap(): Int =
    when (this) {
        "profile" -> MEDIA_PROFILE_ALBUM_PHOTO_CAP
        else -> MEDIA_PRIVATE_ALBUM_PHOTO_CAP
    }
