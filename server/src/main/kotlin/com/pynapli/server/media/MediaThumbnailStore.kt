package com.pynapli.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Storage for grid thumbnails: reads the stored (already screened) original, writes and deletes the
 * derived object at [mediaThumbnailObjectKey]. Same bucket/provider as the original, so the same
 * signature scheme guards reads. Implementations never log URLs, object keys or bytes: asset id and
 * HTTP status only.
 */
interface MediaThumbnailStore {
    /** True when this store can serve assets stored under [storageProvider]. */
    fun supports(storageProvider: String): Boolean

    /** The original's bytes, or throws [IOException] (transient: retried later). */
    suspend fun readOriginal(source: ThumbnailSource): ByteArray

    suspend fun writeThumbnail(
        source: ThumbnailSource,
        bytes: ByteArray,
    )

    /** True when the thumbnail object is gone afterwards (including when it never existed). */
    suspend fun deleteThumbnail(
        storageProvider: String,
        storageBucket: String,
        mediaAssetId: UUID,
    ): Boolean
}

/** What the generator needs to find an original: read from the claimed row, never from a client. */
data class ThumbnailSource(
    val mediaAssetId: UUID,
    val storageProvider: String,
    val storageBucket: String,
    val storageObjectKey: String,
    val byteSize: Long,
)

/**
 * GCS through short-lived V4 signed URLs from the same [GcsV4SignedUrlFactory] the upload signer,
 * download resolver and [GcsMediaObjectDeleter] use: no extra SDK, no extra credentials.
 */
class GcsMediaThumbnailStore(
    private val signedUrlFactory: GcsV4SignedUrlFactory,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(20),
    private val maxOriginalBytes: Long = MAX_ORIGINAL_BYTES,
) : MediaThumbnailStore {
    override fun supports(storageProvider: String): Boolean = storageProvider.equals("gcs", ignoreCase = true)

    override suspend fun readOriginal(source: ThumbnailSource): ByteArray {
        if (source.byteSize > maxOriginalBytes) throw IOException("original too large")
        return withContext(Dispatchers.IO) {
            val signed =
                signedUrlFactory.sign(
                    method = "GET",
                    bucket = source.storageBucket,
                    objectKey = source.storageObjectKey,
                    ttl = Duration.ofMinutes(1),
                )
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(signed.url))
                    .timeout(requestTimeout)
                    .GET()
                    .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) throw IOException("original read returned HTTP ${response.statusCode()}")
            val body = response.body()
            if (body.size > maxOriginalBytes) throw IOException("original too large")
            body
        }
    }

    override suspend fun writeThumbnail(
        source: ThumbnailSource,
        bytes: ByteArray,
    ) {
        withContext(Dispatchers.IO) {
            // Content-Type is signed, so the object is always stored as a JPEG. No create-only
            // precondition: a retried generation overwrites its own earlier attempt.
            val headers = mapOf("content-type" to MediaThumbnails.CONTENT_TYPE)
            val signed =
                signedUrlFactory.sign(
                    method = "PUT",
                    bucket = source.storageBucket,
                    objectKey = mediaThumbnailObjectKey(source.mediaAssetId),
                    ttl = Duration.ofMinutes(1),
                    extraSignedHeaders = headers,
                )
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(signed.url))
                    .timeout(requestTimeout)
                    .header("Content-Type", MediaThumbnails.CONTENT_TYPE)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build()
            val status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status !in 200..299) throw IOException("thumbnail write returned HTTP $status")
        }
    }

    override suspend fun deleteThumbnail(
        storageProvider: String,
        storageBucket: String,
        mediaAssetId: UUID,
    ): Boolean {
        if (!supports(storageProvider)) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val signed =
                    signedUrlFactory.sign(
                        method = "DELETE",
                        bucket = storageBucket,
                        objectKey = mediaThumbnailObjectKey(mediaAssetId),
                        ttl = Duration.ofMinutes(1),
                    )
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(signed.url))
                        .timeout(requestTimeout)
                        .DELETE()
                        .build()
                val status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                // 404: never written, or a retried sweep.
                status in 200..299 || status == 404
            }.getOrDefault(false)
        }
    }

    private companion object {
        // Album photos are images capped well below this; it only bounds a hostile row.
        const val MAX_ORIGINAL_BYTES = 30L * 1024 * 1024
    }
}

/** Local/test storage: the thumbnail sits next to the upload as `{assetId}.thumb.jpg`. */
class LocalMediaThumbnailStore(
    private val storage: LocalMediaUploadStorage,
) : MediaThumbnailStore {
    override fun supports(storageProvider: String): Boolean = true

    override suspend fun readOriginal(source: ThumbnailSource): ByteArray =
        withContext(Dispatchers.IO) {
            runCatching { storage.loadUpload(source.mediaAssetId.toString()).bytes }
                .getOrElse { throw IOException("original missing") }
        }

    override suspend fun writeThumbnail(
        source: ThumbnailSource,
        bytes: ByteArray,
    ) {
        withContext(Dispatchers.IO) { storage.saveThumbnail(source.mediaAssetId, bytes) }
    }

    override suspend fun deleteThumbnail(
        storageProvider: String,
        storageBucket: String,
        mediaAssetId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            storage.deleteThumbnail(mediaAssetId)
            true
        }
}
