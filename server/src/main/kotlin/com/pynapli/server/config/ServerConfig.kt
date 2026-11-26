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
    // review) or "cloud_vision" (Google Cloud Vision SafeSearch pre-screening, requires the GCS
    // signing service account + the cloud-platform scope). Dev auto-approve still wins when set.
    // fromEnvironment defaults staging/prod to cloud_vision (defaultMediaModerationProvider);
    // production refuses manual unless mediaModerationManualInProductionAllowed.
    val mediaModerationProvider: String = "manual",
    val mediaModerationVisionEndpoint: String = "https://vision.googleapis.com/v1/images:annotate",
    // PYNAPLI_MEDIA_MODERATION_ALLOW_MANUAL_IN_PRODUCTION: the explicit, written-down override that
    // lets production run with every profile photo waiting for a person (e.g. a Vision outage).
    val mediaModerationManualInProductionAllowed: Boolean = false,
    // PYNAPLI_MEDIA_MODERATION_SCAN_PRIVATE_MEDIA: founder/legal decision, default off. Off, the
    // scanner (Cloud Vision, a third-party processor) sees public profile photos only; on, private
    // album and chat photos are scanned too, for the severe-violence removal/refusal only.
    val mediaModerationScanPrivateMedia: Boolean = false,
    // SafeSearch auto-approve ceilings (Vision likelihood names). A profile photo is approved only
    // when adult/violence/racy are all at or below these; anything above waits for a person.
    // Defaults: TRUST_AND_SAFETY_SPEC "Photo pre-screening". Validated at startup.
    val mediaModerationMaxAdult: String = "UNLIKELY",
    val mediaModerationMaxViolence: String = "POSSIBLE",
    val mediaModerationMaxRacy: String = "LIKELY",
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
    // Local-only sign-up helpers for seeds and QA (`/v1/dev/auth/email/signup` creates a verified
    // account at once; `/latest-code` reads the local file mailer's last code). Off by default
    // everywhere (PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true turns it on), refused at startup in
    // production-like environments, wired only when the environment is local and the local file
    // mailer is in use, and served only to direct loopback requests without forwarding headers.
    val devSignupShortcutsEnabled: Boolean = false,
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
    // HMAC-SHA256 secret the age-verification provider signs its callbacks with
    // (PYNAPLI_AGE_VERIFICATION_CALLBACK_SECRET). Unset: the callback route isn't served. The provider
    // never holds the admin token. Required in production-like environments when a provider is set.
    val ageVerificationCallbackSecret: String? = null,
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
    // "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact blocks"): K1 keys the stored code
    // of each added address and of each verified member's address; K2 keys which member a row points
    // at. Fixed development values locally; staging and production refuse to start without explicit,
    // strong, distinct values. Rotating either orphans every stored code (founder setup item).
    val contactBlockEmailKey: String = "pynapli-local-dev-contact-block-email-key",
    val contactBlockTargetKey: String = "pynapli-local-dev-contact-block-target-key",
    // Continue with Google / Apple (ONBOARDING_AUTH_SPEC "Social sign-in"). Off by default; each
    // provider is served only when this is on AND that provider is fully configured (SocialSignInSettings).
    val socialSignInEnabled: Boolean = false,
    // PYNAPLI_GOOGLE_CLIENT_IDS: every OAuth client id whose ID tokens are accepted (web + Android).
    val googleClientIds: List<String> = emptyList(),
    // PYNAPLI_APPLE_CLIENT_IDS: the Services ID (web) and the bundle id (iOS).
    val appleClientIds: List<String> = emptyList(),
    // Apple's client-secret JWT (code exchange + revocation): team id, key id and the .p8 key's PEM
    // text (PYNAPLI_APPLE_PRIVATE_KEY, from the secret manager only; never committed).
    val appleTeamId: String? = null,
    val appleKeyId: String? = null,
    val applePrivateKeyPem: String? = null,
    // PYNAPLI_APPLE_REDIRECT_URIS = "servicesId=https://...,...": the redirect_uri a web (Services ID)
    // authorization used, which Apple's code exchange must repeat. Native (bundle id) codes need none.
    val appleRedirectUris: Map<String, String> = emptyMap(),
    // Test/local overrides of the provider endpoints. Refused at startup in staging/production.
    val googleJwksUrl: String? = null,
    val appleJwksUrl: String? = null,
    val appleTokenUrl: String? = null,
    val appleRevokeUrl: String? = null,
    // Keys the at-rest encryption of Apple refresh tokens (PYNAPLI_SOCIAL_TOKEN_KEY). Production-like
    // environments with social sign-in on refuse to start without an explicit, strong, distinct value.
    val socialTokenKey: String = "pynapli-local-dev-social-token-key",
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
                mediaModerationProvider =
                    System.getenv("PYNAPLI_MEDIA_MODERATION_PROVIDER") ?: defaultMediaModerationProvider(environment),
                mediaModerationVisionEndpoint =
                    System.getenv("PYNAPLI_MEDIA_MODERATION_VISION_ENDPOINT")
                        ?: "https://vision.googleapis.com/v1/images:annotate",
                mediaModerationManualInProductionAllowed =
                    System.getenv("PYNAPLI_MEDIA_MODERATION_ALLOW_MANUAL_IN_PRODUCTION")?.toBooleanStrictOrNull() ?: false,
                mediaModerationScanPrivateMedia =
                    System.getenv("PYNAPLI_MEDIA_MODERATION_SCAN_PRIVATE_MEDIA")?.toBooleanStrictOrNull() ?: false,
                mediaModerationMaxAdult = System.getenv("PYNAPLI_MEDIA_MODERATION_MAX_ADULT") ?: "UNLIKELY",
                mediaModerationMaxViolence = System.getenv("PYNAPLI_MEDIA_MODERATION_MAX_VIOLENCE") ?: "POSSIBLE",
                mediaModerationMaxRacy = System.getenv("PYNAPLI_MEDIA_MODERATION_MAX_RACY") ?: "LIKELY",
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
                // Off unless asked for, even locally: a local server is often behind a demo tunnel.
                devSignupShortcutsEnabled =
                    System
                        .getenv("PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED")
                        ?.toBooleanStrictOrNull()
                        ?: false,
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
                ageVerificationCallbackSecret =
                    System
                        .getenv("PYNAPLI_AGE_VERIFICATION_CALLBACK_SECRET")
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
                contactBlockEmailKey =
                    System
                        .getenv("PYNAPLI_CONTACT_BLOCK_EMAIL_KEY")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "pynapli-local-dev-contact-block-email-key",
                contactBlockTargetKey =
                    System
                        .getenv("PYNAPLI_CONTACT_BLOCK_TARGET_KEY")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "pynapli-local-dev-contact-block-target-key",
                socialSignInEnabled =
                    System
                        .getenv("PYNAPLI_SOCIAL_SIGN_IN_ENABLED")
                        ?.trim()
                        ?.toBooleanStrictOrNull()
                        ?: false,
                googleClientIds = commaList(System.getenv("PYNAPLI_GOOGLE_CLIENT_IDS")),
                appleClientIds = commaList(System.getenv("PYNAPLI_APPLE_CLIENT_IDS")),
                appleTeamId = nonBlankEnv("PYNAPLI_APPLE_TEAM_ID"),
                appleKeyId = nonBlankEnv("PYNAPLI_APPLE_KEY_ID"),
                applePrivateKeyPem = nonBlankEnv("PYNAPLI_APPLE_PRIVATE_KEY"),
                appleRedirectUris = appleRedirectUrisFrom(System.getenv("PYNAPLI_APPLE_REDIRECT_URIS")),
                googleJwksUrl = nonBlankEnv("PYNAPLI_GOOGLE_JWKS_URL"),
                appleJwksUrl = nonBlankEnv("PYNAPLI_APPLE_JWKS_URL"),
                appleTokenUrl = nonBlankEnv("PYNAPLI_APPLE_TOKEN_URL"),
                appleRevokeUrl = nonBlankEnv("PYNAPLI_APPLE_REVOKE_URL"),
                socialTokenKey = nonBlankEnv("PYNAPLI_SOCIAL_TOKEN_KEY") ?: "pynapli-local-dev-social-token-key",
            )
        }
    }
}

/**
 * Moderation provider when PYNAPLI_MEDIA_MODERATION_PROVIDER is unset: staging and production
 * pre-screen with Cloud Vision; local/test (and anything unrecognised) hold every upload for a person.
 */
internal fun defaultMediaModerationProvider(environment: String): String = if (environment.trim().lowercase() in setOf("staging", "prod", "production")) "cloud_vision" else "manual"

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

private fun nonBlankEnv(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

internal fun commaList(value: String?): List<String> = value?.split(",")?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.orEmpty()

/** "clientId=uri,clientId=uri"; split on the first '=' so a query string in the URI survives. */
internal fun appleRedirectUrisFrom(value: String?): Map<String, String> =
    commaList(value)
        .mapNotNull { pair ->
            val clientId = pair.substringBefore('=', missingDelimiterValue = "").trim()
            val uri = pair.substringAfter('=', missingDelimiterValue = "").trim()
            if (clientId.isEmpty() || uri.isEmpty()) null else clientId to uri
        }.toMap()

/** Google is usable once the flag is on and at least one client id is accepted. */
fun ServerConfig.googleSignInConfigured(): Boolean = socialSignInEnabled && googleClientIds.isNotEmpty()

/**
 * Apple is usable only when everything deletion needs is there too: Apple requires the grant to be
 * revoked when the account is deleted, which needs the client-secret key.
 */
fun ServerConfig.appleSignInConfigured(): Boolean =
    socialSignInEnabled &&
        appleClientIds.isNotEmpty() &&
        !appleTeamId.isNullOrBlank() &&
        !appleKeyId.isNullOrBlank() &&
        !applePrivateKeyPem.isNullOrBlank()
