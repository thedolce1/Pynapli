package com.pynapli.server.media

import com.pynapli.shared.network.dto.MEDIA_VIDEO_MAX_BYTES
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Clock
import java.util.UUID

class LocalMediaUploadStorage(
    rootDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val rootDirectory = rootDirectory.toAbsolutePath().normalize()

    fun saveUpload(
        mediaAssetIdText: String,
        contentType: String?,
        bytes: ByteArray,
    ): LocalMediaUploadRecord {
        val mediaAssetId = mediaAssetIdText.toUuidOrThrow()
        if (bytes.isEmpty()) throw MediaException("INVALID_MEDIA_BYTE_SIZE")
        if (bytes.size > MEDIA_VIDEO_MAX_BYTES) throw MediaException("MEDIA_FILE_TOO_LARGE")

        val assetDirectory = rootDirectory.resolve(mediaAssetId.toString().take(2)).normalize()
        Files.createDirectories(assetDirectory)

        val dataPath = assetDirectory.resolve("$mediaAssetId.bin")
        val metadataPath = assetDirectory.resolve("$mediaAssetId.meta")
        Files.write(dataPath, bytes, CREATE, TRUNCATE_EXISTING, WRITE)
        Files.writeString(
            metadataPath,
            buildString {
                appendLine("mediaAssetId=$mediaAssetId")
                appendLine("contentType=${contentType ?: "application/octet-stream"}")
                appendLine("byteSize=${bytes.size}")
                appendLine("uploadedAt=${clock.instant()}")
            },
            CREATE,
            TRUNCATE_EXISTING,
            WRITE,
        )

        return LocalMediaUploadRecord(
            mediaAssetId = mediaAssetId,
            dataPath = dataPath,
            metadataPath = metadataPath,
            byteSize = bytes.size,
        )
    }

    fun loadUpload(mediaAssetIdText: String): LocalMediaUploadContent {
        val mediaAssetId = mediaAssetIdText.toUuidOrThrow()
        val paths = mediaAssetId.localPaths()
        if (!Files.exists(paths.dataPath)) throw MediaException("MEDIA_ASSET_NOT_FOUND")

        val metadata =
            if (Files.exists(paths.metadataPath)) {
                Files
                    .readAllLines(paths.metadataPath)
                    .mapNotNull { line ->
                        val separator = line.indexOf('=')
                        if (separator <= 0) {
                            null
                        } else {
                            line.substring(0, separator) to line.substring(separator + 1)
                        }
                    }.toMap()
            } else {
                emptyMap()
            }

        return LocalMediaUploadContent(
            mediaAssetId = mediaAssetId,
            contentType = metadata["contentType"] ?: "application/octet-stream",
            bytes = Files.readAllBytes(paths.dataPath),
        )
    }

    /** Removes a stored upload and its metadata; a missing file is not an error. */
    fun deleteUpload(mediaAssetId: UUID) {
        val paths = mediaAssetId.localPaths()
        Files.deleteIfExists(paths.dataPath)
        Files.deleteIfExists(paths.metadataPath)
    }

    private fun UUID.localPaths(): LocalMediaUploadPaths {
        val assetDirectory = rootDirectory.resolve(toString().take(2)).normalize()
        return LocalMediaUploadPaths(
            dataPath = assetDirectory.resolve("$this.bin"),
            metadataPath = assetDirectory.resolve("$this.meta"),
        )
    }

    private fun String.toUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw MediaException("INVALID_ID") }
}

data class LocalMediaUploadRecord(
    val mediaAssetId: UUID,
    val dataPath: Path,
    val metadataPath: Path,
    val byteSize: Int,
)

data class LocalMediaUploadContent(
    val mediaAssetId: UUID,
    val contentType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is LocalMediaUploadContent &&
            mediaAssetId == other.mediaAssetId &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = mediaAssetId.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

private data class LocalMediaUploadPaths(
    val dataPath: Path,
    val metadataPath: Path,
)
