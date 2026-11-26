package com.pynapli.server.dependencies

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.media.AutoApproveMediaModerationScanner
import com.pynapli.server.media.CdnMediaAssetUrlResolver
import com.pynapli.server.media.CdnSignedUrlFactory
import com.pynapli.server.media.CloudVisionMediaModerationScanner
import com.pynapli.server.media.CloudVisionSelfieFaceDetector
import com.pynapli.server.media.GcsMediaObjectDeleter
import com.pynapli.server.media.GcsV4MediaAssetUrlResolver
import com.pynapli.server.media.GcsV4MediaUploadSigner
import com.pynapli.server.media.GcsV4SignedUrlFactory
import com.pynapli.server.media.JdbcMediaRepository
import com.pynapli.server.media.LocalDevMediaUploadSigner
import com.pynapli.server.media.LocalMediaAssetUrlResolver
import com.pynapli.server.media.LocalMediaObjectDeleter
import com.pynapli.server.media.LocalMediaUploadStorage
import com.pynapli.server.media.ManualReviewMediaModerationScanner
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaModerationScanner
import com.pynapli.server.media.MediaObjectDeleter
import com.pynapli.server.media.MediaScanScope
import com.pynapli.server.media.MediaService
import com.pynapli.server.media.MediaServiceMessageMediaGateway
import com.pynapli.server.media.MediaVerificationSelfieGateway
import com.pynapli.server.media.SafeSearchLikelihood
import com.pynapli.server.media.SafeSearchPolicy
import com.pynapli.server.media.StaticMediaUploadSigner
import com.pynapli.server.media.toLikelihood
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.JdbcReportRepository
import com.pynapli.server.safety.MediaModerationEscalationService
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskService
import com.pynapli.server.verification.SelfieFaceDetector
import com.pynapli.server.verification.SelfieMediaGateway
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
    val messageMediaGateway: com.pynapli.server.messaging.MessageMediaGateway,
    val selfieMediaGateway: SelfieMediaGateway,
    // Face counting for verification selfies; only when Cloud Vision moderation is configured.
    val selfieFaceDetector: SelfieFaceDetector?,
    // Grid thumbnails (G2-2). The resolver is always present when media URLs are; the scheduler and
    // worker only when thumbnails are enabled and the storage can hold them.
    val mediaImageResolver: com.pynapli.server.media.MediaImageResolver? = null,
    val thumbnailScheduler: com.pynapli.server.media.MediaThumbnailScheduler? = null,
    val thumbnailWorker: com.pynapli.server.media.MediaThumbnailWorker? = null,
)

internal fun buildMediaDependencies(
    config: ServerConfig,
    blockRepository: BlockRepository,
    riskService: RiskService,
    rateLimitService: RateLimitService,
    dataSource: DataSource,
    contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
): MediaDependencies {
    if (
        config.mediaStorageProvider.equals("local", ignoreCase = true) &&
        config.environment !in LOCAL_MEDIA_ALLOWED_ENVIRONMENTS
    ) {
        throw IllegalStateException(
            "Local media upload storage is allowed only for local/test environments. " +
                "Use PYNAPLI_MEDIA_STORAGE_PROVIDER=gcs for production-like environments.",
        )
    }

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
                    fallbackBaseUrl = config.mediaUploadBaseUrl,
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
    val mediaRepository = JdbcMediaRepository(dataSource)
    val thumbnails = buildThumbnails(config, dataSource, gcsSignedUrlFactory, localMediaUploadStorage)
    val moderationScanner = createMediaModerationScanner(config, mediaSigningCredentials)
    // Founder decision: Vision sees public profile photos (and, via the face detector below,
    // verification selfies) only, unless private-media scanning is switched on.
    val scanScope = MediaScanScope(scanPrivateMedia = config.mediaModerationScanPrivateMedia)
    val mediaService =
        MediaService(
            repository = mediaRepository,
            uploadSigner = uploadSigner,
            blockRepository = blockRepository,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            moderationScanner = moderationScanner,
            moderationEscalationPublisher =
                MediaModerationEscalationService(
                    reportRepository = JdbcReportRepository(dataSource),
                    riskSignalPublisher = riskService,
                ),
            rateLimitService = rateLimitService,
            contactBlockVisibility = contactBlockVisibility,
            scanScope = scanScope,
            profilePhotoApproved = thumbnails?.scheduler,
        )
    // Chat photo media reuses the same signer/asset/scanner/resolver as album media.
    val messageMediaGateway =
        MediaServiceMessageMediaGateway(
            repository = mediaRepository,
            uploadSigner = uploadSigner,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            moderationScanner = moderationScanner,
            rateLimitService = rateLimitService,
            scanScope = scanScope,
        )
    // Verification selfies ride the same signer/asset rows; unlike album media the app deletes their
    // objects itself after the retention window (PhotoVerificationService.purgeExpiredSelfies).
    val mediaObjectDeleter: MediaObjectDeleter? =
        when {
            gcsSignedUrlFactory != null -> GcsMediaObjectDeleter(gcsSignedUrlFactory)
            localMediaUploadStorage != null -> LocalMediaObjectDeleter(localMediaUploadStorage)
            else -> null
        }
    val selfieMediaGateway =
        MediaVerificationSelfieGateway(
            repository = mediaRepository,
            uploadSigner = uploadSigner,
            // Direct short-lived GCS signature, never the CDN: an edge-cached selfie would outlive its
            // retention delete. Local storage serves straight from disk.
            selfieUrlResolver =
                gcsSignedUrlFactory?.let { factory ->
                    GcsV4MediaAssetUrlResolver(
                        storageBucket = config.mediaStorageBucket,
                        signedUrlFactory = factory,
                        ttl = java.time.Duration.ofMinutes(5),
                    )
                } ?: mediaAssetUrlResolver.takeIf { localMediaUploadStorage != null },
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            objectDeleter = mediaObjectDeleter,
        )
    val selfieFaceDetector = createSelfieFaceDetector(config, mediaSigningCredentials)
    return MediaDependencies(
        mediaAssetUrlResolver = mediaAssetUrlResolver,
        localMediaUploadStorage = localMediaUploadStorage,
        mediaService = mediaService,
        messageMediaGateway = messageMediaGateway,
        selfieMediaGateway = selfieMediaGateway,
        selfieFaceDetector = selfieFaceDetector,
        mediaImageResolver =
            mediaAssetUrlResolver?.let { com.pynapli.server.media.MediaImageResolver(it, thumbnails?.repository) },
        thumbnailScheduler = thumbnails?.scheduler,
        thumbnailWorker = thumbnails?.worker,
    )
}

private class ThumbnailParts(
    val repository: com.pynapli.server.media.MediaThumbnailRepository,
    val scheduler: com.pynapli.server.media.MediaThumbnailScheduler,
    val worker: com.pynapli.server.media.MediaThumbnailWorker,
)

// Thumbnails live in the same storage as the originals: GCS through the shared signing factory, or
// the local upload directory. Any other provider (or thumbnails switched off) serves originals only.
private fun buildThumbnails(
    config: ServerConfig,
    dataSource: DataSource,
    gcsSignedUrlFactory: GcsV4SignedUrlFactory?,
    localMediaUploadStorage: LocalMediaUploadStorage?,
): ThumbnailParts? {
    if (!config.mediaThumbnailsEnabled) return null
    val store: com.pynapli.server.media.MediaThumbnailStore =
        when {
            gcsSignedUrlFactory != null -> com.pynapli.server.media.GcsMediaThumbnailStore(gcsSignedUrlFactory)
            localMediaUploadStorage != null -> com.pynapli.server.media.LocalMediaThumbnailStore(localMediaUploadStorage)
            else -> return null
        }
    val repository = com.pynapli.server.media.JdbcMediaThumbnailRepository(dataSource)
    val service = com.pynapli.server.media.MediaThumbnailService(repository = repository, store = store)
    return ThumbnailParts(
        repository = repository,
        scheduler = com.pynapli.server.media.MediaThumbnailScheduler(service),
        worker =
            com.pynapli.server.media.MediaThumbnailWorker(
                service = service,
                intervalMillis = config.mediaThumbnailIntervalMillis,
                backfillPerTick = config.mediaThumbnailBackfillPerTick,
                sweepPerTick = config.mediaThumbnailSweepPerTick,
                jobLock = com.pynapli.server.db.PostgresJobLock(dataSource),
            ),
    )
})

private val LOCAL_MEDIA_ALLOWED_ENVIRONMENTS = setOf("local", "test")

// GCS V4 signing needs a service-account private key. Prefer a media-specific service
// account (least privilege: storage object access only); fall back to the Firebase service
// account so a single-credential setup also works.
internal fun loadMediaSigningCredentials(config: ServerConfig): ServiceAccountCredentials {
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

// Upload-complete moderation: dev auto-approve wins first (refused at startup outside local/test),
// then the configured provider. "cloud_vision" needs the media signing credentials:
// loadMediaSigningCredentials throws without them, and startup validation names the gap
// (MEDIA_SIGNING_CREDENTIALS_REQUIRED) in staging/prod, so nothing silently degrades to manual.
internal fun createMediaModerationScanner(
    config: ServerConfig,
    mediaSigningCredentials: ServiceAccountCredentials?,
): MediaModerationScanner =
    when {
        config.mediaDevAutoApproveUploads -> AutoApproveMediaModerationScanner()
        config.mediaModerationProvider.equals("cloud_vision", ignoreCase = true) ->
            CloudVisionMediaModerationScanner(
                credentials =
                    checkNotNull(mediaSigningCredentials) {
                        "Cloud Vision moderation requires PYNAPLI_MEDIA_GCS_SIGNING_SA_JSON/PATH (or a Firebase service account)."
                    },
                endpoint = config.mediaModerationVisionEndpoint,
                policy = config.safeSearchPolicy(),
            )

        else -> ManualReviewMediaModerationScanner()
    }

// Verification selfies go to Vision face counting whenever Cloud Vision is the provider; the
// private-media scan flag has no say here (selfies are a deliberate verification submission).
internal fun createSelfieFaceDetector(
    config: ServerConfig,
    credentials: GoogleCredentials?,
): SelfieFaceDetector? =
    if (config.mediaModerationProvider.equals("cloud_vision", ignoreCase = true) && credentials != null) {
        CloudVisionSelfieFaceDetector(
            credentials = credentials,
            endpoint = config.mediaModerationVisionEndpoint,
        )
    } else {
        null
    }

/** The configured auto-approve ceilings; startup validation has already refused unknown names. */
internal fun ServerConfig.safeSearchPolicy(): SafeSearchPolicy =
    SafeSearchPolicy(
        maxAdult = mediaModerationMaxAdult.toCeiling(SafeSearchPolicy.DEFAULT_MAX_ADULT),
        maxViolence = mediaModerationMaxViolence.toCeiling(SafeSearchPolicy.DEFAULT_MAX_VIOLENCE),
        maxRacy = mediaModerationMaxRacy.toCeiling(SafeSearchPolicy.DEFAULT_MAX_RACY),
    )

private fun String.toCeiling(default: SafeSearchLikelihood): SafeSearchLikelihood = toLikelihood().takeIf { it in SafeSearchPolicy.CONFIGURABLE_CEILINGS } ?: default
