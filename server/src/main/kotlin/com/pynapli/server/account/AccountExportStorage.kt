package com.pynapli.server.account

import com.pynapli.server.media.GcsV4SignedUrlFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Where a finished export archive can be downloaded from, and when that link expires. */
data class StoredExport(
    val downloadUrl: String,
    val expiresAt: Instant,
)

interface AccountExportStorage {
    suspend fun store(
        userId: UUID,
        requestId: UUID,
        archive: ExportArchive,
    ): StoredExport

    /** Reads back a stored archive for the local download route; null if absent or [key] is wrong. */
    suspend fun read(
        requestId: UUID,
        key: String,
    ): ExportArchive?
}

/**
 * Local-dev storage: writes the archive under [directory] and serves it via the account
 * data-export download route. A GCS implementation (server-side PUT + signed GET via the V4 factory)
 * drops in behind this interface for production.
 */
class LocalAccountExportStorage(
    private val directory: Path,
    private val downloadBaseUrl: String,
    private val ttl: Duration = Duration.ofDays(7),
    private val clock: Clock = Clock.systemUTC(),
) : AccountExportStorage {
    override suspend fun store(
        userId: UUID,
        requestId: UUID,
        archive: ExportArchive,
    ): StoredExport =
        withContext(Dispatchers.IO) {
            // The key is the download link's secret; it lives only in the file name and the link.
            val key = newKey()
            Files.createDirectories(directory)
            Files.write(directory.resolve(fileName(requestId, key)), archive.bytes)
            StoredExport(
                downloadUrl = "${downloadBaseUrl.trimEnd('/')}/v1/account/data-export/$requestId/download?key=$key",
                expiresAt = clock.instant().plus(ttl),
            )
        }

    override suspend fun read(
        requestId: UUID,
        key: String,
    ): ExportArchive? =
        withContext(Dispatchers.IO) {
            // Strict format check first: the key becomes part of a file path.
            if (!KEY_PATTERN.matches(key)) return@withContext null
            val path = directory.resolve(fileName(requestId, key))
            if (!Files.exists(path)) {
                null
            } else {
                ExportArchive(
                    filename = "pynapli-data-export.json",
                    contentType = "application/json",
                    bytes = Files.readAllBytes(path),
                )
            }
        }

    private fun fileName(
        requestId: UUID,
        key: String,
    ) = "$requestId-$key.json"

    private fun newKey(): String {
        val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_BYTES = 32
        val KEY_PATTERN = Regex("[0-9a-f]{64}")
        val random = SecureRandom()
    }
}

/** Uploads bytes to a pre-signed URL. Seam so the GCS storage is testable without a bucket. */
fun interface ExportObjectUploader {
    /** PUTs [bytes] to [signedUrl]; throws if the store doesn't accept them. */
    suspend fun put(
        signedUrl: String,
        bytes: ByteArray,
        contentType: String,
    )
}

/** JDK HttpClient uploader: no extra dependency; blocking I/O stays on [Dispatchers.IO]. */
class JdkExportObjectUploader(
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val requestTimeout: Duration = Duration.ofMinutes(2),
) : ExportObjectUploader {
    override suspend fun put(
        signedUrl: String,
        bytes: ByteArray,
        contentType: String,
    ) = withContext(Dispatchers.IO) {
        val request =
            HttpRequest
                .newBuilder(URI.create(signedUrl))
                .timeout(requestTimeout)
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build()
        val status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        // The status only: the signed URL is a bearer credential and must not reach the logs.
        check(status in 200..299) { "Export upload rejected with HTTP $status" }
    }
}

/**
 * Production storage: the server PUTs the archive into a private GCS bucket through a short-lived
 * V4 signed URL, then hands the user a V4 signed GET URL valid for [ttl] (GCS caps V4 at 7 days).
 * Downloads go straight to GCS, so [read] returns null and the API download route isn't used.
 * Pair the bucket with a lifecycle rule that deletes `account-exports/` objects after [ttl].
 */
class GcsAccountExportStorage(
    private val signer: GcsV4SignedUrlFactory,
    private val bucket: String,
    private val uploader: ExportObjectUploader = JdkExportObjectUploader(),
    private val ttl: Duration = Duration.ofDays(7),
) : AccountExportStorage {
    override suspend fun store(
        userId: UUID,
        requestId: UUID,
        archive: ExportArchive,
    ): StoredExport {
        val objectKey = "$OBJECT_PREFIX$userId/$requestId.json"
        val upload = signer.sign(method = "PUT", bucket = bucket, objectKey = objectKey, ttl = UPLOAD_URL_TTL)
        uploader.put(upload.url, archive.bytes, archive.contentType)
        val download = signer.sign(method = "GET", bucket = bucket, objectKey = objectKey, ttl = ttl)
        return StoredExport(
            downloadUrl = download.url,
            expiresAt = Instant.ofEpochSecond(download.expiresAtEpochSeconds),
        )
    }

    override suspend fun read(
        requestId: UUID,
        key: String,
    ): ExportArchive? = null

    companion object {
        const val OBJECT_PREFIX = "account-exports/"
        private val UPLOAD_URL_TTL: Duration = Duration.ofMinutes(15)
    }
}
