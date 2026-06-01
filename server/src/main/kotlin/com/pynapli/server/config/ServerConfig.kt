package com.pynapli.server.config

data class ServerConfig(
    val environment: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val databaseMaximumPoolSize: Int = 60,
    val databaseMinimumIdle: Int = 1,
    val databaseConnectionTimeoutMillis: Long = 5_000L,
    val apiProfilingEnabled: Boolean = false,
    val apiProfilingHeaderRequired: Boolean = true,
    val apiProfilingSlowSpanThresholdMillis: Double = 250.0,
    val redisUrl: String,
    val mediaStorageProvider: String = "local",
    val mediaStorageBucket: String = "pynapli-local-media",
    val mediaUploadBaseUrl: String = "http://10.0.2.2:8080",
    val mediaLocalUploadDirectory: String = ".pynapli-local-media",
    val mediaDevAutoApproveUploads: Boolean = false,
    // Automated moderation provider for upload-complete: "manual" (hold every upload for human
    // review, the safe default) or "cloud_vision" (Google Cloud Vision SafeSearch, requires the GCS
    // signing service account + the cloud-platform scope). Dev auto-approve still wins when set.
    val mediaModerationProvider: String = "manual",
    val mediaModerationVisionEndpoint: String = "https://vision.googleapis.com/v1/images:annotate",
    val mediaGcsSigningRegion: String = "auto",
    val mediaGcsSigningServiceAccountPath: String? = null,
    val mediaGcsSigningServiceAccountJson: String? = null,
    // Download delivery path for gcs storage: "signed_gcs" (default; direct GCS V4 signed GET) or
    // "cdn" (Cloud CDN signed URL via a CDN-backed bucket — cheaper/faster at scale). "cdn" needs
    // the CDN base URL + a signing key/key-name provisioned on the backend bucket (founder/GCP step).
    val mediaDeliveryProvider: String = "signed_gcs",
    val mediaCdnBaseUrl: String? = null,
    val mediaCdnSigningKeyName: String? = null,
    val mediaCdnSigningKeyBase64Url: String? = null,
    val notificationDispatchEnabled: Boolean = false,
    val notificationDispatchIntervalMillis: Long = 5_000L,
    val notificationDispatchBatchSize: Int = 50,
    val notificationMaxAttempts: Int = 5,
    val notificationPushProvider: String = "local",
    val activityRetentionEnabled: Boolean = false,
    val activityRetentionIntervalMillis: Long = 60 * 60 * 1_000L,
    val mediaCleanupEnabled: Boolean = false,
    val mediaCleanupIntervalMillis: Long = 60 * 60 * 1_000L,
    val firebaseServiceAccountPath: String? = null,
    val firebaseServiceAccountJson: String? = null,
    val playStorePackageName: String = "com.pynapli.app",
    val playStoreVerificationEnabled: Boolean = false,
    val playStoreServiceAccountPath: String? = null,
    val playStoreServiceAccountJson: String? = null,
    val playStoreDeveloperApiBaseUrl: String = "https://androidpublisher.googleapis.com",
    val playStoreSubscriptionSyncEnabled: Boolean = false,
    val playStoreSubscriptionSyncIntervalMillis: Long = 5 * 60 * 1_000L,
    val playStoreSubscriptionSyncBatchSize: Int = 100,
    val playStoreSubscriptionSyncStaleAfterHours: Long = 6,
    val playStoreSubscriptionSyncRenewalLookaheadHours: Long = 24,
    val playStoreRtdnEnabled: Boolean = false,
    val playStoreRtdnWebhookToken: String? = null,
    val playStoreRtdnOidcAudience: String? = null,
    val playStoreRtdnOidcServiceAccountEmail: String? = null,
    val devStorePurchasesEnabled: Boolean = false,
    val adminApiToken: String? = null,
    val supportEmail: String = "support@pynapli.com",
    val discoveryCursorSecret: String = "pynapli-local-dev-discovery-cursor-secret",
    val activityCursorSecret: String = "pynapli-local-dev-activity-cursor-secret",
    val swipeDeckTokenSecret: String = "pynapli-local-dev-swipe-deck-token-secret",
) {
    companion object {
        fun fromEnvironment(): ServerConfig {
            val environment = System.getenv("PYNAPLI_ENV") ?: "local"
            val port = System.getenv("PYNAPLI_SERVER_PORT")?.toIntOrNull() ?: 8080
            val mediaStorageProvider = System.getenv("PYNAPLI_MEDIA_STORAGE_PROVIDER") ?: "local"
            val defaultUploadBaseUrl =
                if (mediaStorageProvider.equals("local", ignoreCase = true)) {
                    "http://10.0.2.2:$port"
                } else {
                    "https://storage.googleapis.com"
                }

            return ServerConfig(
                environment = environment,
                port = port,
                databaseUrl = System.getenv("PYNAPLI_DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/pynapli_dev",
                databaseUser = System.getenv("PYNAPLI_DATABASE_USER") ?: "pynapli",
                databasePassword = System.getenv("PYNAPLI_DATABASE_PASSWORD") ?: "pynapli_dev_password",
                databaseMaximumPoolSize =
                    System
                        .getenv("PYNAPLI_DATABASE_MAX_POOL_SIZE")
                        ?.toIntOrNull()
                        ?.coerceIn(1, 100)
                        ?: 60,
                databaseMinimumIdle =
                    System
                        .getenv("PYNAPLI_DATABASE_MIN_IDLE")
                        ?.toIntOrNull()
                        ?.coerceIn(0, 100)
                        ?: 1,
                databaseConnectionTimeoutMillis =
                    System
                        .getenv("PYNAPLI_DATABASE_CONNECTION_TIMEOUT_MS")
                        ?.toLongOrNull()
                        ?.coerceIn(1_000L, 60_000L)
                        ?: 5_000L,
                apiProfilingEnabled =
                    System.getenv("PYNAPLI_API_PROFILING_ENABLED")?.toBooleanStrictOrNull()
                        ?: false,
                apiProfilingHeaderRequired =
                    System
                        .getenv("PYNAPLI_API_PROFILING_HEADER_REQUIRED")
                        ?.toBooleanStrictOrNull()
                        ?: true,
                apiProfilingSlowSpanThresholdMillis =
                    System
                        .getenv("PYNAPLI_API_PROFILING_SLOW_SPAN_MS")
                        ?.toDoubleOrNull()
                        ?.coerceAtLeast(0.0)
                        ?: 250.0,
                redisUrl = System.getenv("PYNAPLI_REDIS_URL") ?: "redis://localhost:6379",
                mediaStorageProvider = mediaStorageProvider,
                mediaStorageBucket = System.getenv("PYNAPLI_MEDIA_STORAGE_BUCKET") ?: "pynapli-local-media",
                mediaUploadBaseUrl = System.getenv("PYNAPLI_MEDIA_UPLOAD_BASE_URL") ?: defaultUploadBaseUrl,
                mediaLocalUploadDirectory = System.getenv("PYNAPLI_MEDIA_LOCAL_UPLOAD_DIR") ?: ".pynapli-local-media",
                mediaDevAutoApproveUploads =
                    System.getenv("PYNAPLI_MEDIA_DEV_AUTO_APPROVE_UPLOADS")?.toBooleanStrictOrNull()
                        ?: (environment == "local"),
                mediaModerationProvider = System.getenv("PYNAPLI_MEDIA_MODERATION_PROVIDER") ?: "manual",
                mediaModerationVisionEndpoint =
                    System.getenv("PYNAPLI_MEDIA_MODERATION_VISION_ENDPOINT")
                        ?: "https://vision.googleapis.com/v1/images:annotate",
                mediaGcsSigningRegion = System.getenv("PYNAPLI_MEDIA_GCS_SIGNING_REGION") ?: "auto",
                mediaGcsSigningServiceAccountPath = System.getenv("PYNAPLI_MEDIA_GCS_SIGNING_SA_PATH"),
                mediaGcsSigningServiceAccountJson = System.getenv("PYNAPLI_MEDIA_GCS_SIGNING_SA_JSON"),
                mediaDeliveryProvider = System.getenv("PYNAPLI_MEDIA_DELIVERY_PROVIDER") ?: "signed_gcs",
                mediaCdnBaseUrl = System.getenv("PYNAPLI_MEDIA_CDN_BASE_URL"),
                mediaCdnSigningKeyName = System.getenv("PYNAPLI_MEDIA_CDN_SIGNING_KEY_NAME"),
                mediaCdnSigningKeyBase64Url = System.getenv("PYNAPLI_MEDIA_CDN_SIGNING_KEY_BASE64URL"),
                notificationDispatchEnabled =
                    System.getenv("PYNAPLI_NOTIFICATION_DISPATCH_ENABLED")?.toBooleanStrictOrNull()
                        ?: (environment == "local"),
                notificationDispatchIntervalMillis =
                    System
                        .getenv("PYNAPLI_NOTIFICATION_DISPATCH_INTERVAL_MS")
                        ?.toLongOrNull()
                        ?.coerceAtLeast(1_000L)
                        ?: 5_000L,
                notificationDispatchBatchSize =
                    System
                        .getenv("PYNAPLI_NOTIFICATION_DISPATCH_BATCH_SIZE")
                        ?.toIntOrNull()
                        ?.coerceIn(1, 250)
                        ?: 50,
                notificationMaxAttempts =
                    System
                        .getenv("PYNAPLI_NOTIFICATION_MAX_ATTEMPTS")
                        ?.toIntOrNull()
                        ?.coerceIn(1, 20)
                        ?: 5,
                notificationPushProvider =
                    System
                        .getenv("PYNAPLI_NOTIFICATION_PUSH_PROVIDER")
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "local",
                activityRetentionEnabled =
                    System.getenv("PYNAPLI_ACTIVITY_RETENTION_ENABLED")?.toBooleanStrictOrNull()
                        ?: (environment == "local"),
                activityRetentionIntervalMillis =
                    System
                        .getenv("PYNAPLI_ACTIVITY_RETENTION_INTERVAL_MS")
                        ?.toLongOrNull()
                        ?.coerceAtLeast(60_000L)
                        ?: 60 * 60 * 1_000L,
                mediaCleanupEnabled =
                    System.getenv("PYNAPLI_MEDIA_CLEANUP_ENABLED")?.toBooleanStrictOrNull()
                        ?: (environment == "local"),
                mediaCleanupIntervalMillis =
                    System
                        .getenv("PYNAPLI_MEDIA_CLEANUP_INTERVAL_MS")
                        ?.toLongOrNull()
                        ?.coerceAtLeast(60_000L)
                        ?: 60 * 60 * 1_000L,
                firebaseServiceAccountPath =
                    System
                        .getenv("PYNAPLI_FIREBASE_SERVICE_ACCOUNT_PATH")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                firebaseServiceAccountJson =
                    System
                        .getenv("PYNAPLI_FIREBASE_SERVICE_ACCOUNT_JSON")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                playStorePackageName =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_PACKAGE_NAME")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "com.pynapli.app",
                playStoreVerificationEnabled =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_VERIFICATION_ENABLED")
                        ?.toBooleanStrictOrNull()
                        ?: false,
                playStoreServiceAccountPath =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_SERVICE_ACCOUNT_PATH")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                playStoreServiceAccountJson =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_SERVICE_ACCOUNT_JSON")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                playStoreDeveloperApiBaseUrl =
                    System
                        .getenv(
                            "PYNAPLI_PLAY_STORE_DEVELOPER_API_BASE_URL",
                        )?.trim()
                        ?.trimEnd('/')
                        ?.takeIf { it.isNotEmpty() }
                        ?: "https://androidpublisher.googleapis.com",
                playStoreSubscriptionSyncEnabled =
                    System
                        .getenv(
                            "PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_ENABLED",
                        )?.toBooleanStrictOrNull()
                        ?: (System.getenv("PYNAPLI_PLAY_STORE_VERIFICATION_ENABLED")?.toBooleanStrictOrNull() ?: false),
                playStoreSubscriptionSyncIntervalMillis =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_INTERVAL_MS")
                        ?.toLongOrNull()
                        ?.coerceAtLeast(60_000L)
                        ?: 5 * 60 * 1_000L,
                playStoreSubscriptionSyncBatchSize =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_BATCH_SIZE")
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500)
                        ?: 100,
                playStoreSubscriptionSyncStaleAfterHours =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_STALE_AFTER_HOURS")
                        ?.toLongOrNull()
                        ?.coerceIn(1, 168)
                        ?: 6,
                playStoreSubscriptionSyncRenewalLookaheadHours =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_RENEWAL_LOOKAHEAD_HOURS")
                        ?.toLongOrNull()
                        ?.coerceIn(1, 168)
                        ?: 24,
                playStoreRtdnEnabled =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_RTDN_ENABLED")
                        ?.toBooleanStrictOrNull()
                        ?: false,
                playStoreRtdnWebhookToken =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_RTDN_WEBHOOK_TOKEN")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                playStoreRtdnOidcAudience =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_RTDN_OIDC_AUDIENCE")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                playStoreRtdnOidcServiceAccountEmail =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_RTDN_OIDC_SERVICE_ACCOUNT_EMAIL")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                devStorePurchasesEnabled =
                    System
                        .getenv("PYNAPLI_DEV_STORE_PURCHASES_ENABLED")
                        ?.toBooleanStrictOrNull()
                        ?: (environment == "local"),
                adminApiToken =
                    System
                        .getenv("PYNAPLI_ADMIN_API_TOKEN")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                supportEmail =
                    System
                        .getenv("PYNAPLI_SUPPORT_EMAIL")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "support@pynapli.com",
                discoveryCursorSecret =
                    System
                        .getenv("PYNAPLI_DISCOVERY_CURSOR_SECRET")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "pynapli-local-dev-discovery-cursor-secret",
                activityCursorSecret =
                    System
                        .getenv("PYNAPLI_ACTIVITY_CURSOR_SECRET")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "pynapli-local-dev-activity-cursor-secret",
                swipeDeckTokenSecret =
                    System
                        .getenv("PYNAPLI_SWIPE_DECK_TOKEN_SECRET")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "pynapli-local-dev-swipe-deck-token-secret",
            )
        }
    }
}
