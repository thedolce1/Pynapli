package com.pynapli.server.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
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

    /** Reads back a stored archive for the local download route; null if absent. */
    suspend fun read(requestId: UUID): ExportArchive?
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
            Files.createDirectories(directory)
            Files.write(directory.resolve("$requestId.json"), archive.bytes)
            StoredExport(
                downloadUrl = "${downloadBaseUrl.trimEnd('/')}/v1/account/data-export/$requestId/download",
                expiresAt = clock.instant().plus(ttl),
            )
        }

    override suspend fun read(requestId: UUID): ExportArchive? =
        withContext(Dispatchers.IO) {
            val path = directory.resolve("$requestId.json")
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
}
