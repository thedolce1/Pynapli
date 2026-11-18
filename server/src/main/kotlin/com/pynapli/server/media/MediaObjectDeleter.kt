package com.pynapli.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Deletes one stored media object. Album media is expired by bucket lifecycle rules; this exists for
 * objects the app must remove on its own schedule (verification selfies, see PhotoVerificationService).
 */
interface MediaObjectDeleter {
    /** True when the object is gone afterwards (including when it was already missing). */
    suspend fun delete(
        storageProvider: String,
        storageBucket: String,
        storageObjectKey: String,
        mediaAssetId: UUID,
    ): Boolean
}

/**
 * GCS delete through a short-lived V4 signed `DELETE` URL from the same [GcsV4SignedUrlFactory] the
 * upload signer uses, so it needs no extra SDK and no extra credentials. Logs only the asset id and
 * HTTP status, never the URL or object key.
 */
class GcsMediaObjectDeleter(
    private val signedUrlFactory: GcsV4SignedUrlFactory,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(10),
) : MediaObjectDeleter {
    override suspend fun delete(
        storageProvider: String,
        storageBucket: String,
        storageObjectKey: String,
        mediaAssetId: UUID,
    ): Boolean {
        if (!storageProvider.equals("gcs", ignoreCase = true)) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val signed =
                    signedUrlFactory.sign(
                        method = "DELETE",
                        bucket = storageBucket,
                        objectKey = storageObjectKey,
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
                // 404: already gone (a retried sweep, or the bucket lifecycle got there first).
                val deleted = status in 200..299 || status == 404
                if (!deleted) logger.warn("Media object delete for asset {} returned HTTP {}", mediaAssetId, status)
                deleted
            }.getOrElse { error ->
                logger.warn("Media object delete for asset {} failed: {}", mediaAssetId, error.javaClass.simpleName)
                false
            }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(GcsMediaObjectDeleter::class.java)
    }
}

/** Local/test storage: removes the stored file. */
class LocalMediaObjectDeleter(
    private val storage: LocalMediaUploadStorage,
) : MediaObjectDeleter {
    override suspend fun delete(
        storageProvider: String,
        storageBucket: String,
        storageObjectKey: String,
        mediaAssetId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            storage.deleteUpload(mediaAssetId)
            true
        }
}
