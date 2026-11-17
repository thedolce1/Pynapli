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
    val databaseCloudSqlInstance: String? = null,
    val databaseCloudSqlUnixSocketPath: String? = null,
    val databaseCloudSqlRefreshStrategy: String = "lazy",
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
    /** Bearer token for GET /metrics; unset = endpoint not installed. */
    val metricsToken: String? = null,
    // On everywhere by default: it backs the disappearing-messages promise (DataRetentionService).
    val dataRetentionEnabled: Boolean = true,
    val dataRetentionIntervalMillis: Long = 60 * 60 * 1_000L,
    val expiredMessageGraceDays: Long = 30,
    /** Proxies in front of the API that append to X-Forwarded-For (Cloud Run: 1). */
    val trustedProxyHops: Int = 1,
    val activityRetentionEnabled: Boolean = false,
    val activityRetentionIntervalMillis: Long = 60 * 60 * 1_000L,
    val mediaCleanupEnabled: Boolean = false,
    val mediaCleanupIntervalMillis: Long = 60 * 60 * 1_000L,
    val accountDataExportEnabled: Boolean = false,
    val accountDataExportIntervalMillis: Long = 60_000L,
    val accountDataExportDirectory: String = ".pynapli-account-exports",
    /** GCS bucket for export archives when media storage is `gcs`; null = the media bucket. */
    val accountDataExportBucket: String? = null,
    val firebaseServiceAccountPath: String? = null,
    val firebaseServiceAccountJson: String? = null,
    // Optional explicit Firebase/GCP project id. When the server authenticates via Application
    // Default Credentials (no service-account JSON — the keyless gcloud-ADC / Cloud Run path), the
    // project id may not be inferable, so set it here (e.g. "pynapli") to route FCM reliably.
    val firebaseProjectId: String? = null,
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
    // Sandbox Play Billing verification: when enabled (and not production), sandbox-prefixed purchase
    // tokens are accepted by the real submit -> verify -> grant path WITHOUT Google creds, so end-to-end
    // receipt validation is provable in dev/QA. Opt-in; never selected in production.
    val playStoreSandboxEnabled: Boolean = false,
    // Pluggable age-verification provider. When ageVerificationProvider + redirect base URL are both
    // set, the server advertises a provider-hosted method alongside manual review and mints a redirect
    // session on start(). The provider confirms the outcome later via the verification callback. Real
    // provider integrations (handshake/webhook) are an ops step; the default impl is dependency-free.
    val ageVerificationProvider: String? = null,
    val ageVerificationProviderLabel: String = "Identity provider",
    val ageVerificationProviderRedirectBaseUrl: String? = null,
    // Travel mode destination rules by country, e.g. "AE=blocked,QA=age_verified"
    // (PYNAPLI_TRAVEL_REGION_POLICY). Empty: no destination needs anything extra.
    val travelRegionPolicy: String? = null,
    val adminApiToken: String? = null,
    // Optional admin role map (PYNAPLI_ADMIN_ROLES = "id:role,id:role"). When empty, role enforcement
    // is off and every authenticated admin is treated as senior (pre-roles behavior). When set, an
    // unmapped actor defaults to operator (least privilege) and sensitive actions require senior.
    val adminRoles: Map<String, String> = emptyMap(),
    // Step-up MFA for sensitive admin actions. When enforced, those endpoints require a valid TOTP
    // code (X-Admin-MFA) for the actor's base32 secret. Off by default (local/dev/test unaffected).
    val adminMfaEnforced: Boolean = false,
    val adminMfaSecrets: Map<String, String> = emptyMap(),
    // App Check boundary: when true, state-changing requests must carry a verified
    // `X-Firebase-AppCheck` header (attesting a genuine app instance). Default off so local/dev/test
    // never require it; flip on in production only after the client attaches the token.
    val appCheckEnforced: Boolean = false,
    // Firebase project NUMBER (e.g. "209241465883"). When set with enforcement on, App Check tokens
    // are cryptographically verified (RS256 JWT vs Google's App Check JWKS, issuer/audience keyed to
    // this number). When absent, enforcement falls back to requiring a non-blank header only.
    val appCheckProjectNumber: String? = null,
    // Browser origins allowed to call the API (the web PWA). Full `scheme://host[:port]`
    // strings, comma-separated in PYNAPLI_CORS_ALLOWED_ORIGINS. Local default covers the
    // wasm dev server; every non-local environment defaults to https://app.pynapli.com so a
    // deploy that forgets the env var still admits the PWA (override to add/replace origins).
    val corsAllowedOrigins: List<String> = emptyList(),
    val supportEmail: String = "support@pynapli.com",
    // How forgot-password codes are delivered: "log" (local only — codes go to the git-ignored
    // .pynapli-local-mail/reset-codes.txt, never the server log) or
    // "none" (reset answers PASSWORD_RESET_UNAVAILABLE). A real email provider is a founder setup item.
    val passwordResetMailer: String = "none",
    val discoveryCursorSecret: String = "pynapli-local-dev-discovery-cursor-secret",
    val activityCursorSecret: String = "pynapli-local-dev-activity-cursor-secret",
    val swipeDeckTokenSecret: String = "pynapli-local-dev-swipe-deck-token-secret",
) {
    companion object {
        fun fromEnvironment(): ServerConfig {
            val environmentVariables = System.getenv()
            val environment = environmentVariables["PYNAPLI_ENV"] ?: "local"
            val port = serverPortFromEnvironment(environmentVariables)
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
                databaseCloudSqlInstance =
                    System
                        .getenv("PYNAPLI_DATABASE_CLOUD_SQL_INSTANCE")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                databaseCloudSqlUnixSocketPath =
                    System
                        .getenv("PYNAPLI_DATABASE_CLOUD_SQL_UNIX_SOCKET_PATH")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                databaseCloudSqlRefreshStrategy =
                    System
                        .getenv("PYNAPLI_DATABASE_CLOUD_SQL_REFRESH_STRATEGY")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "lazy",
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
                metricsToken = System.getenv("PYNAPLI_METRICS_TOKEN")?.trim()?.takeIf { it.length >= 16 },
                dataRetentionEnabled =
                    System.getenv("PYNAPLI_DATA_RETENTION_ENABLED")?.toBooleanStrictOrNull() ?: true,
                dataRetentionIntervalMillis =
                    System
                        .getenv("PYNAPLI_DATA_RETENTION_INTERVAL_MS")
                        ?.toLongOrNull()
                        ?.coerceAtLeast(60_000L)
                        ?: 60 * 60 * 1_000L,
                // How long an expired disappearing message stays (hidden) for moderation before it is deleted.
                expiredMessageGraceDays =
                    System
                        .getenv("PYNAPLI_EXPIRED_MESSAGE_GRACE_DAYS")
                        ?.toLongOrNull()
                        ?.coerceIn(0L, 365L)
                        ?: 30,
                trustedProxyHops =
                    System
                        .getenv("PYNAPLI_TRUSTED_PROXY_HOPS")
                        ?.toIntOrNull()
                        ?.coerceIn(0, 5)
                        ?: 1,
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
                accountDataExportEnabled =
                    System.getenv("PYNAPLI_ACCOUNT_DATA_EXPORT_ENABLED")?.toBooleanStrictOrNull()
                        ?: (environment == "local"),
                accountDataExportIntervalMillis =
                    System
                        .getenv("PYNAPLI_ACCOUNT_DATA_EXPORT_INTERVAL_MS")
                        ?.toLongOrNull()
                        ?.coerceAtLeast(10_000L)
                        ?: 60_000L,
                accountDataExportDirectory =
                    System.getenv("PYNAPLI_ACCOUNT_DATA_EXPORT_DIR") ?: ".pynapli-account-exports",
                accountDataExportBucket = System.getenv("PYNAPLI_ACCOUNT_DATA_EXPORT_BUCKET")?.takeIf { it.isNotBlank() },
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
                firebaseProjectId =
                    System
                        .getenv("PYNAPLI_FIREBASE_PROJECT_ID")
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
                playStoreSandboxEnabled =
                    System
                        .getenv("PYNAPLI_PLAY_STORE_SANDBOX_ENABLED")
                        ?.toBooleanStrictOrNull()
                        ?: false,
                travelRegionPolicy =
                    System
                        .getenv("PYNAPLI_TRAVEL_REGION_POLICY")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                ageVerificationProvider =
                    System
                        .getenv("PYNAPLI_AGE_VERIFICATION_PROVIDER")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                ageVerificationProviderLabel =
                    System
                        .getenv("PYNAPLI_AGE_VERIFICATION_PROVIDER_LABEL")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "Identity provider",
                ageVerificationProviderRedirectBaseUrl =
                    System
                        .getenv("PYNAPLI_AGE_VERIFICATION_PROVIDER_REDIRECT_BASE_URL")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                adminApiToken =
                    System
                        .getenv("PYNAPLI_ADMIN_API_TOKEN")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                adminRoles =
                    (System.getenv("PYNAPLI_ADMIN_ROLES") ?: "")
                        .split(",")
                        .mapNotNull { entry ->
                            val parts = entry.split(":", limit = 2)
                            if (parts.size != 2) {
                                null
                            } else {
                                val id = parts[0].trim()
                                val role = parts[1].trim().lowercase()
                                if (id.isEmpty() || role.isEmpty()) null else id to role
                            }
                        }.toMap(),
                adminMfaEnforced =
                    System
                        .getenv("PYNAPLI_ADMIN_MFA_ENFORCED")
                        ?.toBooleanStrictOrNull()
                        ?: false,
                adminMfaSecrets =
                    (System.getenv("PYNAPLI_ADMIN_MFA_SECRETS") ?: "")
                        .split(",")
                        .mapNotNull { entry ->
                            val parts = entry.split(":", limit = 2)
                            if (parts.size != 2) {
                                null
                            } else {
                                val id = parts[0].trim()
                                val secret = parts[1].trim()
                                if (id.isEmpty() || secret.isEmpty()) null else id to secret
                            }
                        }.toMap(),
                appCheckEnforced =
                    System
                        .getenv("PYNAPLI_APP_CHECK_ENFORCED")
                        ?.toBooleanStrictOrNull()
                        ?: false,
                appCheckProjectNumber =
                    System
                        .getenv("PYNAPLI_APP_CHECK_PROJECT_NUMBER")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                corsAllowedOrigins =
                    corsAllowedOriginsFromEnvironment(environmentVariables, environment),
                passwordResetMailer =
                    System.getenv("PYNAPLI_PASSWORD_RESET_MAILER")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: if (environment == "local") "log" else "none",
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

internal fun serverPortFromEnvironment(environmentVariables: Map<String, String>): Int =
    environmentVariables["PYNAPLI_SERVER_PORT"]?.toIntOrNull()
        ?: environmentVariables["PORT"]?.toIntOrNull()
        ?: 8080

// Browser origins allowed to call the API (the web PWA at app.pynapli.com). Explicit
// PYNAPLI_CORS_ALLOWED_ORIGINS (comma-separated) wins; otherwise local serves the wasm dev
// server and every other environment defaults to the production PWA origin so a deploy that
// forgets the env var still admits the app instead of silently blocking every browser request.
internal fun corsAllowedOriginsFromEnvironment(
    environmentVariables: Map<String, String>,
    environment: String,
): List<String> {
    val explicit =
        environmentVariables["PYNAPLI_CORS_ALLOWED_ORIGINS"]
            ?.split(",")
            ?.mapNotNull { origin -> origin.trim().takeIf { it.isNotEmpty() } }
    return when {
        explicit != null -> explicit
        environment == "local" -> listOf("http://localhost:8081")
        else -> listOf("https://app.pynapli.com")
    }
}
