package com.pynapli.server.dependencies

import com.google.auth.oauth2.ServiceAccountCredentials
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.media.AutoApproveMediaModerationScanner
import com.pynapli.server.media.CdnMediaAssetUrlResolver
import com.pynapli.server.media.CdnSignedUrlFactory
import com.pynapli.server.media.CloudVisionMediaModerationScanner
import com.pynapli.server.media.GcsV4MediaAssetUrlResolver
import com.pynapli.server.media.GcsV4MediaUploadSigner
import com.pynapli.server.media.GcsV4SignedUrlFactory
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalDevMediaUploadSigner
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.LocalMediaUploadStorage
import com.pynapli.server.media.ManualReviewMediaModerationScanner
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaModerationScanner
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.JdbcReportRepository
import com.pynapli.server.safety.MediaModerationEscalationService
import com.pynapli.server.safety.RiskService
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Media subsystem wiring extracted from `ServerDependencies.fromEnvironment` to keep the DI root a
 * thin orchestrator. Builds the storage/CDN signing chain, the asset-URL resolver, the upload
 * signer/storage, and the [MediaService] (with its upload-complete moderation scanner). Only the
 * three members other subsystems consume escape via [MediaDependencies]; the signing credentials,
 * factories, upload signer, and repository stay internal to the builder. Behavior matches the
 * original inline construction verbatim.
 */
internal class MediaDependencies(
    val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    val localMediaUploadStorage: LocalMediaUploadStorage?,
    val mediaService: MediaService,
)

internal fun buildMediaDependencies(
    config: ServerConfig,
    blockRepository: BlockRepository,
    riskService: RiskService,
    dataSource: DataSource,
): MediaDependencies {
    // Media signing credentials, loaded once and shared by the GCS signer and the Cloud
    // Vision moderation scanner (both ride the same service account).
    val mediaSigningCredentials =
        if (config.mediaStorageProvider.equals("gcs", ignoreCase = true) ||
            config.mediaModerationProvider.equals("cloud_vision", ignoreCase = true)
        ) {
            loadMediaSigningCredentials(config)
        } else {
            null
        }
    // One signing factory shared by the upload signer and the download resolver (gcs only).
    val gcsSignedUrlFactory =
        if (config.mediaStorageProvider.equals("gcs", ignoreCase = true)) {
            GcsV4SignedUrlFactory(
                credentials = mediaSigningCredentials!!,
                region = config.mediaGcsSigningRegion,
            )
        } else {
            null
        }
    // CDN signed-URL factory for the "cdn" delivery path (gcs storage only). Built only when
    // the CDN base URL + key name + key are all configured; otherwise delivery falls back to
    // direct GCS V4 signed URLs below.
    val cdnSignedUrlFactory =
        if (config.mediaStorageProvider.equals("gcs", ignoreCase = true) &&
            config.mediaDeliveryProvider.equals("cdn", ignoreCase = true) &&
            !config.mediaCdnBaseUrl.isNullOrBlank() &&
            !config.mediaCdnSigningKeyName.isNullOrBlank() &&
            !config.mediaCdnSigningKeyBase64Url.isNullOrBlank()
        ) {
            CdnSignedUrlFactory(
                keyName = config.mediaCdnSigningKeyName,
                base64UrlKey = config.mediaCdnSigningKeyBase64Url,
            )
        } else {
            null
        }
    val mediaAssetUrlResolver: MediaAssetUrlResolver? =
        when (config.mediaStorageProvider.lowercase()) {
            "local" -> LocalMediaAssetUrlResolver(config.mediaUploadBaseUrl)
            "gcs" ->
                if (cdnSignedUrlFactory != null) {
                    CdnMediaAssetUrlResolver(
                        cdnBaseUrl = config.mediaCdnBaseUrl!!,
                        signedUrlFactory = cdnSignedUrlFactory,
                    )
                } else {
                    GcsV4MediaAssetUrlResolver(
                        storageBucket = config.mediaStorageBucket,
                        signedUrlFactory = gcsSignedUrlFactory!!,
                    )
                }

            else -> null
        }
    val localMediaUploadStorage =
        if (config.mediaStorageProvider.equals("local", ignoreCase = true)) {
            LocalMediaUploadStorage(Path.of(config.mediaLocalUploadDirectory))
        } else {
            null
        }
    val uploadSigner =
        when (config.mediaStorageProvider.lowercase()) {
            "local" ->
                LocalDevMediaUploadSigner(
                    storageBucket = config.mediaStorageBucket,
                    uploadBaseUrl = config.mediaUploadBaseUrl,
                )

            "gcs" ->
                GcsV4MediaUploadSigner(
                    storageBucket = config.mediaStorageBucket,
                    signedUrlFactory = gcsSignedUrlFactory!!,
                )

            else ->
                StaticMediaUploadSigner(
                    storageProvider = config.mediaStorageProvider,
                    storageBucket = config.mediaStorageBucket,
                    uploadBaseUrl = config.mediaUploadBaseUrl,
                )
        }
    val mediaService =
        MediaService(
            repository = JdbcMediaRepository(dataSource),
            uploadSigner = uploadSigner,
            blockRepository = blockRepository,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            moderationScanner = createMediaModerationScanner(config, mediaSigningCredentials),
            moderationEscalationPublisher =
                MediaModerationEscalationService(
                    reportRepository = JdbcReportRepository(dataSource),
                    riskSignalPublisher = riskService,
                ),
        )
    return MediaDependencies(
        mediaAssetUrlResolver = mediaAssetUrlResolver,
        localMediaUploadStorage = localMediaUploadStorage,
        mediaService = mediaService,
    )
}

// GCS V4 signing needs a service-account private key. Prefer a media-specific service
// account (least privilege: storage object access only); fall back to the Firebase service
// account so a single-credential setup also works.
private fun loadMediaSigningCredentials(config: ServerConfig): ServiceAccountCredentials {
    val json = config.mediaGcsSigningServiceAccountJson ?: config.firebaseServiceAccountJson
    if (json != null) {
        return ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
            .use(ServiceAccountCredentials::fromStream)
    }
    val path = config.mediaGcsSigningServiceAccountPath ?: config.firebaseServiceAccountPath
    if (path != null) {
        return FileInputStream(path).use(ServiceAccountCredentials::fromStream)
    }
    throw IllegalStateException(
        "GCS media storage requires PYNAPLI_MEDIA_GCS_SIGNING_SA_JSON/PATH " +
            "(or a Firebase service account) to sign upload URLs.",
    )
}

// Upload-complete moderation: dev auto-approve wins first, then the configured provider.
// "cloud_vision" needs the media signing credentials; if they're absent we fall back to
// manual review rather than silently auto-approving (fail-safe).
private fun createMediaModerationScanner(
    config: ServerConfig,
    mediaSigningCredentials: ServiceAccountCredentials?,
): MediaModerationScanner =
    when {
        config.mediaDevAutoApproveUploads -> AutoApproveMediaModerationScanner()
        config.mediaModerationProvider.equals("cloud_vision", ignoreCase = true) &&
            mediaSigningCredentials != null ->
            CloudVisionMediaModerationScanner(
                credentials = mediaSigningCredentials,
                endpoint = config.mediaModerationVisionEndpoint,
            )

        else -> ManualReviewMediaModerationScanner()
    }
