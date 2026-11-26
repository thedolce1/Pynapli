package com.pynapli.server.config

data class ServerConfigIssue(
    val code: String,
    val message: String,
)

class ServerConfigException(
    val issues: List<ServerConfigIssue>,
) : IllegalStateException(
        "Invalid Pynapli server configuration: ${issues.joinToString { it.code }}",
    )

data class ServerStartupSummary(
    val environment: String,
    val port: Int,
    val mediaStorageProvider: String,
    // "dev_auto_approve", "manual" or "cloud_vision": which upload-complete scanner is wired.
    val mediaModeration: String,
    val notificationPushProvider: String,
    val appCheckEnforced: Boolean,
    val cloudSqlConfigured: Boolean,
    val redisConfigured: Boolean,
)

fun ServerConfig.validateForStartup() {
    val issues = startupValidationIssues()
    if (issues.isNotEmpty()) {
        throw ServerConfigException(issues)
    }
}

fun ServerConfig.startupSummary(): ServerStartupSummary =
    ServerStartupSummary(
        environment = environment,
        port = port,
        mediaStorageProvider = mediaStorageProvider,
        mediaModeration = if (mediaDevAutoApproveUploads) "dev_auto_approve" else mediaModerationProvider.lowercase(),
        notificationPushProvider = notificationPushProvider,
        appCheckEnforced = appCheckEnforced,
        cloudSqlConfigured = !databaseCloudSqlInstance.isNullOrBlank(),
        redisConfigured = redisUrl.isNotBlank(),
    )

internal fun ServerConfig.startupValidationIssues(): List<ServerConfigIssue> =
    buildList {
        validateCommonSettings(this@startupValidationIssues)
        validateCorsOrigins(this@startupValidationIssues)
        validateProductionLikeSettings(this@startupValidationIssues)
        validateProductionOnlySettings(this@startupValidationIssues)
    }

private fun MutableList<ServerConfigIssue>.validateCommonSettings(config: ServerConfig) {
    // A typo ("prodution") would otherwise run as a non-production environment and skip every
    // production-only guard.
    require(
        condition = config.environment.trim().lowercase() in KNOWN_ENVIRONMENTS,
        code = "UNKNOWN_ENVIRONMENT",
        message = "PYNAPLI_ENV must be one of local, test, staging, prod or production.",
    )
    require(
        condition = config.port in 1..65_535,
        code = "INVALID_PORT",
        message = "PYNAPLI_SERVER_PORT/PORT must be between 1 and 65535.",
    )
    require(
        condition = config.mediaStorageProvider.lowercase() in MEDIA_STORAGE_PROVIDERS,
        code = "INVALID_MEDIA_STORAGE_PROVIDER",
        message = "PYNAPLI_MEDIA_STORAGE_PROVIDER must be local or gcs.",
    )
    require(
        condition = config.mediaModerationProvider.lowercase() in MEDIA_MODERATION_PROVIDERS,
        code = "INVALID_MEDIA_MODERATION_PROVIDER",
        message = "PYNAPLI_MEDIA_MODERATION_PROVIDER must be manual or cloud_vision.",
    )
    // A typo in a ceiling must fail startup, never fall back to a looser or stricter default.
    // VERY_LIKELY is refused: it would switch screening off for that category.
    // Violence stops at POSSIBLE: LIKELY is always the severe signal, so a LIKELY ceiling would be inert.
    require(
        condition =
            listOf(config.mediaModerationMaxAdult, config.mediaModerationMaxRacy)
                .all { it.trim().uppercase() in MEDIA_MODERATION_CEILINGS } &&
                config.mediaModerationMaxViolence.trim().uppercase() in MEDIA_MODERATION_CEILINGS - "LIKELY",
        code = "INVALID_MEDIA_MODERATION_THRESHOLD",
        message =
            "PYNAPLI_MEDIA_MODERATION_MAX_ADULT/_MAX_RACY must each be VERY_UNLIKELY, UNLIKELY, POSSIBLE or LIKELY; " +
                "PYNAPLI_MEDIA_MODERATION_MAX_VIOLENCE must be VERY_UNLIKELY, UNLIKELY or POSSIBLE.",
    )
    require(
        condition = config.mediaDeliveryProvider.lowercase() in MEDIA_DELIVERY_PROVIDERS,
        code = "INVALID_MEDIA_DELIVERY_PROVIDER",
        message = "PYNAPLI_MEDIA_DELIVERY_PROVIDER must be signed_gcs or cdn.",
    )
    require(
        condition = config.notificationPushProvider.lowercase() in NOTIFICATION_PUSH_PROVIDERS,
        code = "INVALID_NOTIFICATION_PUSH_PROVIDER",
        message = "PYNAPLI_NOTIFICATION_PUSH_PROVIDER must be local, firebase, or fcm.",
    )
    if (config.mediaDeliveryProvider.equals("cdn", ignoreCase = true)) {
        requireNonBlank(
            value = config.mediaCdnBaseUrl,
            code = "MEDIA_CDN_BASE_URL_REQUIRED",
            message = "CDN media delivery requires PYNAPLI_MEDIA_CDN_BASE_URL.",
        )
        requireNonBlank(
            value = config.mediaCdnSigningKeyName,
            code = "MEDIA_CDN_KEY_NAME_REQUIRED",
            message = "CDN media delivery requires PYNAPLI_MEDIA_CDN_SIGNING_KEY_NAME.",
        )
        requireNonBlank(
            value = config.mediaCdnSigningKeyBase64Url,
            code = "MEDIA_CDN_SIGNING_KEY_REQUIRED",
            message = "CDN media delivery requires PYNAPLI_MEDIA_CDN_SIGNING_KEY_BASE64URL.",
        )
    }
    if (config.playStoreRtdnEnabled) {
        require(
            condition =
                !config.playStoreRtdnWebhookToken.isNullOrBlank() ||
                    (
                        !config.playStoreRtdnOidcAudience.isNullOrBlank() &&
                            !config.playStoreRtdnOidcServiceAccountEmail.isNullOrBlank()
                    ),
            code = "PLAY_STORE_RTDN_AUTH_REQUIRED",
            message = "Play RTDN requires a webhook token or OIDC audience plus service-account email.",
        )
    }
}

/**
 * Browser origins allowed to call the API (the web PWA). CORS gates browser-originated requests
 * only — native mobile clients are unaffected — so an empty list is a legitimate configuration for
 * a mobile-only deployment and is deliberately NOT an error here.
 *
 * What is checked:
 *
 * - Parseability, in every environment. [com.pynapli.server.plugins.configureHTTP] splits each
 *   entry on "://" and silently ignores anything it cannot split, which is the right default (a
 *   malformed entry must never widen access) but means a scheme-less typo like "app.pynapli.com"
 *   disables the web app in production with no error logged anywhere. Fail at startup instead.
 * - No wildcards in production-like environments. `allowHost("*")` is not how Ktor expresses
 *   "any origin" (that is `anyHost()`), so a "*" entry does not do what whoever wrote it expected —
 *   either way it has no place in a production allowlist.
 * - https only in production-like environments, and no loopback hosts. A plaintext or localhost
 *   origin in a production allowlist is a misconfiguration, and admitting an http:// origin
 *   undercuts the transport guarantees the rest of this file enforces.
 */
private fun MutableList<ServerConfigIssue>.validateCorsOrigins(config: ServerConfig) {
    config.corsAllowedOrigins.forEach { origin ->
        require(
            condition = origin.isParsableCorsOrigin(),
            code = "CORS_ORIGIN_MALFORMED",
            message =
                "PYNAPLI_CORS_ALLOWED_ORIGINS entries must be scheme://host[:port] with no trailing " +
                    "path or slash; '$origin' would be silently ignored.",
        )
    }

    if (!config.isProductionLikeEnvironment()) return

    config.corsAllowedOrigins.forEach { origin ->
        require(
            condition = !origin.contains("*"),
            code = "CORS_ORIGIN_WILDCARD_FORBIDDEN",
            message = "Production-like CORS origins must be exact; '$origin' contains a wildcard.",
        )
        require(
            condition = origin.startsWith("https://", ignoreCase = true),
            code = "CORS_ORIGIN_MUST_BE_HTTPS",
            message = "Production-like CORS origins must use https; '$origin' does not.",
        )
        require(
            condition = !origin.isLoopbackCorsOrigin(),
            code = "CORS_ORIGIN_NOT_PRODUCTION_SAFE",
            message = "Production-like environments must not allow loopback CORS origins; '$origin' is local.",
        )
    }
}

private fun MutableList<ServerConfigIssue>.validateProductionLikeSettings(config: ServerConfig) {
    if (!config.isProductionLikeEnvironment()) return

    validateSocialSignIn(config)
    require(
        condition = !config.databaseUrl.isLocalPostgresUrl(),
        code = "DATABASE_URL_NOT_PRODUCTION_SAFE",
        message = "Production-like environments must not use the local Docker PostgreSQL URL.",
    )
    require(
        condition = config.databasePassword.isProductionSecretValue(),
        code = "DATABASE_PASSWORD_NOT_PRODUCTION_SAFE",
        message = "Production-like environments must set a non-default database password.",
    )
    require(
        condition = !config.redisUrl.isLocalRedisUrl(),
        code = "REDIS_URL_NOT_PRODUCTION_SAFE",
        message = "Production-like environments must not use the local Docker Redis URL.",
    )
    require(
        condition = config.appCheckEnforced,
        code = "APP_CHECK_ENFORCEMENT_REQUIRED",
        message = "Production-like environments must enable Firebase App Check enforcement.",
    )
    require(
        condition = config.appCheckProjectNumber?.all(Char::isDigit) == true,
        code = "APP_CHECK_PROJECT_NUMBER_REQUIRED",
        message = "Production-like App Check enforcement requires the Firebase project number.",
    )
    require(
        condition = config.mediaStorageProvider.equals("gcs", ignoreCase = true),
        code = "GCS_MEDIA_STORAGE_REQUIRED",
        message = "Production-like environments must use GCS media storage.",
    )
    require(
        condition = config.mediaStorageBucket.isProductionBucketName(),
        code = "MEDIA_BUCKET_NOT_PRODUCTION_SAFE",
        message = "Production-like environments must set a real GCS media bucket.",
    )
    require(
        condition = !config.mediaDevAutoApproveUploads,
        code = "MEDIA_DEV_AUTO_APPROVE_FORBIDDEN",
        message = "Production-like environments must not auto-approve media uploads.",
    )
    if (config.mediaStorageProvider.equals("gcs", ignoreCase = true) ||
        config.mediaModerationProvider.equals("cloud_vision", ignoreCase = true)
    ) {
        require(
            condition = config.hasMediaSigningCredentials(),
            code = "MEDIA_SIGNING_CREDENTIALS_REQUIRED",
            message = "GCS media storage or Cloud Vision moderation requires signing credentials.",
        )
    }
    require(
        condition =
            config.notificationPushProvider.equals("firebase", ignoreCase = true) ||
                config.notificationPushProvider.equals("fcm", ignoreCase = true),
        code = "FIREBASE_PUSH_PROVIDER_REQUIRED",
        message = "Production-like environments must send pushes through Firebase/FCM.",
    )
    requireNonBlank(
        value = config.firebaseProjectId,
        code = "FIREBASE_PROJECT_ID_REQUIRED",
        message = "Production-like Firebase/FCM requires PYNAPLI_FIREBASE_PROJECT_ID.",
    )
    require(
        condition = config.adminApiToken.isStrongRuntimeSecret(),
        code = "ADMIN_API_TOKEN_REQUIRED",
        message = "Production-like admin routes require a strong PYNAPLI_ADMIN_API_TOKEN.",
    )
    if (!config.ageVerificationProvider.isNullOrBlank()) {
        require(
            condition =
                config.ageVerificationCallbackSecret.isStrongRuntimeSecret() &&
                    config.ageVerificationCallbackSecret != config.adminApiToken,
            code = "AGE_VERIFICATION_CALLBACK_SECRET_REQUIRED",
            message =
                "An age-verification provider requires a strong PYNAPLI_AGE_VERIFICATION_CALLBACK_SECRET, " +
                    "different from the admin token.",
        )
    }
    require(
        condition = !config.devStorePurchasesEnabled,
        code = "DEV_STORE_PURCHASES_FORBIDDEN",
        message = "Production-like environments must not enable dev store purchases.",
    )
    require(
        condition = !config.devSignupShortcutsEnabled,
        code = "DEV_SIGNUP_SHORTCUTS_FORBIDDEN",
        message = "Production-like environments must not enable the dev sign-up shortcuts.",
    )
    require(
        condition =
            config.discoveryCursorSecret.isStrongRuntimeSecret() &&
                config.activityCursorSecret.isStrongRuntimeSecret() &&
                config.swipeDeckTokenSecret.isStrongRuntimeSecret(),
        code = "RUNTIME_CURSOR_SECRETS_REQUIRED",
        message = "Production-like cursor/deck secrets must be explicit, strong values.",
    )
    require(
        condition =
            setOf(
                config.discoveryCursorSecret,
                config.activityCursorSecret,
                config.swipeDeckTokenSecret,
            ).size == 3,
        code = "RUNTIME_CURSOR_SECRETS_MUST_DIFFER",
        message = "Discovery, Activity, and Swipe deck secrets must be different values.",
    )
    require(
        condition =
            config.contactBlockEmailKey.isStrongRuntimeSecret() &&
                config.contactBlockTargetKey.isStrongRuntimeSecret(),
        code = "CONTACT_BLOCK_KEYS_REQUIRED",
        message = "Production-like environments need explicit, strong PYNAPLI_CONTACT_BLOCK_EMAIL_KEY and PYNAPLI_CONTACT_BLOCK_TARGET_KEY values.",
    )
    require(
        condition =
            setOf(
                config.contactBlockEmailKey,
                config.contactBlockTargetKey,
                config.discoveryCursorSecret,
                config.activityCursorSecret,
                config.swipeDeckTokenSecret,
            ).size == 5,
        code = "CONTACT_BLOCK_KEYS_MUST_DIFFER",
        message = "The two contact block keys must differ from each other and from every cursor/deck secret.",
    )
    require(
        condition = !config.apiProfilingEnabled || config.apiProfilingHeaderRequired,
        code = "API_PROFILING_HEADER_REQUIRED",
        message = "API profiling may be enabled only when gated by X-Pynapli-Profile.",
    )
}

private fun MutableList<ServerConfigIssue>.validateProductionOnlySettings(config: ServerConfig) {
    if (!config.isProductionEnvironment()) return

    require(
        condition = !config.playStoreSandboxEnabled,
        code = "PLAY_STORE_SANDBOX_FORBIDDEN",
        message = "Production must not enable sandbox Play Billing verification.",
    )
    // Manual moderation in production means every new member's photo waits for a person, so the
    // grid fills with photo-less tiles. It is allowed only with the explicit override.
    require(
        condition =
            !config.mediaModerationProvider.equals("manual", ignoreCase = true) ||
                config.mediaModerationManualInProductionAllowed,
        code = "MEDIA_MODERATION_MANUAL_FORBIDDEN",
        message =
            "Production must pre-screen photos: set PYNAPLI_MEDIA_MODERATION_PROVIDER=cloud_vision, or set " +
                "PYNAPLI_MEDIA_MODERATION_ALLOW_MANUAL_IN_PRODUCTION=true to run manual review on purpose.",
    )
}

/**
 * Continue with Google / Apple in staging/production: the provider endpoints are never overridden
 * (those overrides exist for tests and local fakes only), Apple is either fully configured or not at
 * all, and the key sealing Apple refresh tokens is explicit, strong and used for nothing else.
 */
private fun MutableList<ServerConfigIssue>.validateSocialSignIn(config: ServerConfig) {
    require(
        condition =
            listOf(config.googleJwksUrl, config.appleJwksUrl, config.appleTokenUrl, config.appleRevokeUrl).all { it.isNullOrBlank() },
        code = "SOCIAL_PROVIDER_ENDPOINT_OVERRIDE_FORBIDDEN",
        message = "PYNAPLI_GOOGLE_JWKS_URL / PYNAPLI_APPLE_JWKS_URL / PYNAPLI_APPLE_TOKEN_URL / PYNAPLI_APPLE_REVOKE_URL are for tests only.",
    )
    if (!config.socialSignInEnabled) return
    val appleParts = listOf(config.appleTeamId, config.appleKeyId, config.applePrivateKeyPem)
    require(
        condition = config.appleClientIds.isEmpty() || appleParts.none { it.isNullOrBlank() },
        code = "APPLE_SIGN_IN_INCOMPLETE",
        message = "Sign in with Apple needs PYNAPLI_APPLE_TEAM_ID, PYNAPLI_APPLE_KEY_ID and PYNAPLI_APPLE_PRIVATE_KEY (deletion must revoke Apple tokens).",
    )
    require(
        condition =
            config.socialTokenKey.isStrongRuntimeSecret() &&
                config.socialTokenKey !in
                setOf(
                    config.contactBlockEmailKey,
                    config.contactBlockTargetKey,
                    config.discoveryCursorSecret,
                    config.activityCursorSecret,
                    config.swipeDeckTokenSecret,
                ),
        code = "SOCIAL_TOKEN_KEY_REQUIRED",
        message = "Social sign-in in production-like environments needs an explicit, strong PYNAPLI_SOCIAL_TOKEN_KEY that differs from every other secret.",
    )
}

private fun MutableList<ServerConfigIssue>.require(
    condition: Boolean,
    code: String,
    message: String,
) {
    if (!condition) add(ServerConfigIssue(code, message))
}

private fun MutableList<ServerConfigIssue>.requireNonBlank(
    value: String?,
    code: String,
    message: String,
) {
    require(!value.isNullOrBlank(), code, message)
}

private fun ServerConfig.isProductionLikeEnvironment(): Boolean = environment.trim().lowercase() in PRODUCTION_LIKE_ENVIRONMENTS

private fun ServerConfig.isProductionEnvironment(): Boolean = environment.trim().lowercase() in PRODUCTION_ENVIRONMENTS

private fun String.isLocalPostgresUrl(): Boolean {
    val normalized = lowercase()
    return normalized == "jdbc:postgresql://localhost:5432/pynapli_dev" ||
        normalized == "jdbc:postgresql://127.0.0.1:5432/pynapli_dev" ||
        normalized == "jdbc:postgresql://host.docker.internal:5432/pynapli_dev" ||
        normalized.contains("localhost") ||
        normalized.contains("127.0.0.1") ||
        normalized.contains("host.docker.internal") ||
        normalized.contains("pynapli_dev") ||
        normalized.contains("pynapli_test")
}

private fun String.isLocalRedisUrl(): Boolean {
    val normalized = lowercase()
    return normalized == "redis://localhost:6379" ||
        normalized == "redis://127.0.0.1:6379" ||
        normalized == "redis://host.docker.internal:6379" ||
        normalized.contains("localhost") ||
        normalized.contains("127.0.0.1") ||
        normalized.contains("host.docker.internal")
}

// Mirrors the scheme/host split in configureHTTP deliberately: this check is only meaningful if it
// agrees with what the plugin actually does with the value. Also rejects a trailing path or slash,
// which would be passed to allowHost() as part of the host and never match a real Origin header.
private fun String.isParsableCorsOrigin(): Boolean {
    val scheme = substringBefore("://", missingDelimiterValue = "")
    val host = substringAfter("://", missingDelimiterValue = "")
    return scheme.isNotEmpty() && host.isNotEmpty() && !host.contains("/")
}

private fun String.isLoopbackCorsOrigin(): Boolean {
    val normalized = lowercase()
    return normalized.contains("localhost") ||
        normalized.contains("127.0.0.1") ||
        normalized.contains("[::1]") ||
        normalized.contains("host.docker.internal")
}

private fun String.isProductionSecretValue(): Boolean = isStrongRuntimeSecret() && this != LOCAL_DATABASE_PASSWORD

private fun String?.isStrongRuntimeSecret(): Boolean =
    !isNullOrBlank() &&
        length >= MINIMUM_RUNTIME_SECRET_LENGTH &&
        !contains("local-dev", ignoreCase = true) &&
        !contains("test-", ignoreCase = true) &&
        !contains("dev_", ignoreCase = true) &&
        !contains("replace-with", ignoreCase = true)

private fun String.isProductionBucketName(): Boolean =
    isNotBlank() &&
        this != LOCAL_MEDIA_BUCKET &&
        !contains("local", ignoreCase = true) &&
        !contains("dev", ignoreCase = true)

private fun ServerConfig.hasMediaSigningCredentials(): Boolean =
    !mediaGcsSigningServiceAccountJson.isNullOrBlank() ||
        !mediaGcsSigningServiceAccountPath.isNullOrBlank() ||
        !firebaseServiceAccountJson.isNullOrBlank() ||
        !firebaseServiceAccountPath.isNullOrBlank()

private const val LOCAL_DATABASE_PASSWORD = "pynapli_dev_password"
private const val LOCAL_MEDIA_BUCKET = "pynapli-local-media"
private const val MINIMUM_RUNTIME_SECRET_LENGTH = 32

private val PRODUCTION_LIKE_ENVIRONMENTS = setOf("staging", "prod", "production")
private val PRODUCTION_ENVIRONMENTS = setOf("prod", "production")
private val KNOWN_ENVIRONMENTS = setOf("local", "test") + PRODUCTION_LIKE_ENVIRONMENTS
private val MEDIA_STORAGE_PROVIDERS = setOf("local", "gcs")
private val MEDIA_MODERATION_PROVIDERS = setOf("manual", "cloud_vision")

// Mirrors SafeSearchPolicy.CONFIGURABLE_CEILINGS (config must not depend on the media package).
private val MEDIA_MODERATION_CEILINGS = setOf("VERY_UNLIKELY", "UNLIKELY", "POSSIBLE", "LIKELY")
private val MEDIA_DELIVERY_PROVIDERS = setOf("signed_gcs", "cdn")
private val NOTIFICATION_PUSH_PROVIDERS = setOf("local", "firebase", "fcm")
