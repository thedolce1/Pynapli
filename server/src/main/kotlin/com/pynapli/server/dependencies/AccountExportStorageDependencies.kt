package com.pynapli.server.dependencies

import com.pynapli.server.account.AccountExportStorage
import com.pynapli.server.account.GcsAccountExportStorage
import com.pynapli.server.account.LocalAccountExportStorage
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.media.GcsV4SignedUrlFactory
import java.nio.file.Path

/**
 * Export archives follow the media storage provider: `gcs` writes them to a private bucket and hands
 * out signed download URLs; `local` (allowed only in local/test, enforced by the media wiring) keeps
 * them on disk behind the API download route.
 */
internal fun buildAccountExportStorage(config: ServerConfig): AccountExportStorage =
    if (config.mediaStorageProvider.equals("gcs", ignoreCase = true)) {
        GcsAccountExportStorage(
            signer =
                GcsV4SignedUrlFactory(
                    credentials = loadMediaSigningCredentials(config),
                    region = config.mediaGcsSigningRegion,
                ),
            bucket = config.accountDataExportBucket ?: config.mediaStorageBucket,
        )
    } else {
        LocalAccountExportStorage(
            directory = Path.of(config.accountDataExportDirectory),
            downloadBaseUrl = config.mediaUploadBaseUrl,
        )
    }
