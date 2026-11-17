package com.pynapli.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerConfigValidationTest {
    @Test
    fun localEnvironmentAllowsDeveloperDefaults() {
        assertEquals(emptyList(), localConfig().startupValidationIssues())
    }

    @Test
    fun stagingEnvironmentRejectsLocalDefaultsAndMissingRuntimeSecrets() {
        val issueCodes = stagingWithLocalDefaults().startupValidationIssues().map { it.code }.toSet()

        assertTrue("DATABASE_URL_NOT_PRODUCTION_SAFE" in issueCodes)
        assertTrue("DATABASE_PASSWORD_NOT_PRODUCTION_SAFE" in issueCodes)
        assertTrue("REDIS_URL_NOT_PRODUCTION_SAFE" in issueCodes)
        assertTrue("APP_CHECK_ENFORCEMENT_REQUIRED" in issueCodes)
        assertTrue("APP_CHECK_PROJECT_NUMBER_REQUIRED" in issueCodes)
        assertTrue("GCS_MEDIA_STORAGE_REQUIRED" in issueCodes)
        assertTrue("MEDIA_BUCKET_NOT_PRODUCTION_SAFE" in issueCodes)
        assertTrue("FIREBASE_PUSH_PROVIDER_REQUIRED" in issueCodes)
        assertTrue("FIREBASE_PROJECT_ID_REQUIRED" in issueCodes)
        assertTrue("ADMIN_API_TOKEN_REQUIRED" in issueCodes)
        assertTrue("RUNTIME_CURSOR_SECRETS_REQUIRED" in issueCodes)
    }

    @Test
    fun stagingEnvironmentAcceptsProductionSafeRuntimeConfig() {
        assertEquals(emptyList(), validStagingConfig().startupValidationIssues())
    }

    @Test
    fun productionEnvironmentRejectsSandboxPlayBilling() {
        val issueCodes =
            validStagingConfig(
                environment = "production",
                playStoreSandboxEnabled = true,
            ).startupValidationIssues().map { it.code }

        assertEquals(listOf("PLAY_STORE_SANDBOX_FORBIDDEN"), issueCodes)
    }

    @Test
    fun incompleteCdnConfigFailsFastInAnyEnvironment() {
        val issueCodes =
            localConfig(mediaDeliveryProvider = "cdn")
                .startupValidationIssues()
                .map { it.code }
                .toSet()

        assertTrue("MEDIA_CDN_BASE_URL_REQUIRED" in issueCodes)
        assertTrue("MEDIA_CDN_KEY_NAME_REQUIRED" in issueCodes)
        assertTrue("MEDIA_CDN_SIGNING_KEY_REQUIRED" in issueCodes)
    }

    @Test
    fun playStoreRtdnRequiresStaticTokenOrOidcConfig() {
        val issueCodes =
            localConfig(playStoreRtdnEnabled = true)
                .startupValidationIssues()
                .map { it.code }

        assertEquals(listOf("PLAY_STORE_RTDN_AUTH_REQUIRED"), issueCodes)
    }

    @Test
    fun productionLikeCorsOriginsMustBeHttpsNonLocalAndExact() {
        val issueCodes =
            validStagingConfig(
                corsAllowedOrigins =
                    listOf(
                        "http://app.pynapli.com",
                        "https://localhost:8081",
                        "https://*.pynapli.com",
                    ),
            ).startupValidationIssues().map { it.code }.toSet()

        assertTrue("CORS_ORIGIN_MUST_BE_HTTPS" in issueCodes)
        assertTrue("CORS_ORIGIN_NOT_PRODUCTION_SAFE" in issueCodes)
        assertTrue("CORS_ORIGIN_WILDCARD_FORBIDDEN" in issueCodes)
    }

    @Test
    fun productionLikeCorsAcceptsRealPwaOrigins() {
        val config =
            validStagingConfig(
                corsAllowedOrigins = listOf("https://app.pynapli.com", "https://staging.pynapli.com:8443"),
            )

        assertEquals(emptyList(), config.startupValidationIssues())
    }

    @Test
    fun anAgeVerificationProviderNeedsItsOwnStrongCallbackSecret() {
        val withProvider = validStagingConfig().copy(ageVerificationProvider = "persona")
        assertEquals(listOf("AGE_VERIFICATION_CALLBACK_SECRET_REQUIRED"), withProvider.startupValidationIssues().map { it.code })
        assertEquals(
            listOf("AGE_VERIFICATION_CALLBACK_SECRET_REQUIRED"),
            withProvider.copy(ageVerificationCallbackSecret = withProvider.adminApiToken).startupValidationIssues().map { it.code },
            "reusing the admin token defeats the point",
        )
        assertEquals(
            emptyList(),
            withProvider.copy(ageVerificationCallbackSecret = "provider-callback-signing-secret-0123456789").startupValidationIssues(),
        )
    }

    @Test
    fun emptyCorsOriginsStayValidForAMobileOnlyDeployment() {
        // CORS gates browser requests only, so a deployment with no web client is legitimate.
        assertEquals(emptyList(), validStagingConfig(corsAllowedOrigins = emptyList()).startupValidationIssues())
    }

    @Test
    fun malformedCorsOriginFailsFastInAnyEnvironment() {
        // configureHTTP silently ignores an entry it cannot split on "://", so without this the web
        // app would just stop working in production with nothing logged.
        val issueCodes =
            localConfig(corsAllowedOrigins = listOf("app.pynapli.com"))
                .startupValidationIssues()
                .map { it.code }

        assertEquals(listOf("CORS_ORIGIN_MALFORMED"), issueCodes)
    }

    @Test
    fun corsOriginWithTrailingPathIsRejected() {
        // allowHost() would receive "app.pynapli.com/" as the host and never match a real Origin.
        val issueCodes =
            localConfig(corsAllowedOrigins = listOf("https://app.pynapli.com/"))
                .startupValidationIssues()
                .map { it.code }

        assertEquals(listOf("CORS_ORIGIN_MALFORMED"), issueCodes)
    }

    @Test
    fun localCorsOriginsMayStayHttpLoopback() {
        // The local default is http://localhost:8081 for the wasm dev server; only production-like
        // environments get the https/non-loopback rules.
        assertEquals(
            emptyList(),
            localConfig(corsAllowedOrigins = listOf("http://localhost:8081")).startupValidationIssues(),
        )
    }

    @Test
    fun validateForStartupThrowsAllIssueCodes() {
        val error = assertFailsWith<ServerConfigException> { stagingWithLocalDefaults().validateForStartup() }

        assertTrue(error.issues.size >= 10)
        assertTrue(error.message.orEmpty().contains("DATABASE_URL_NOT_PRODUCTION_SAFE"))
    }

    private fun localConfig(
        mediaDeliveryProvider: String = "signed_gcs",
        playStoreRtdnEnabled: Boolean = false,
        corsAllowedOrigins: List<String> = emptyList(),
    ): ServerConfig =
        ServerConfig(
            environment = "local",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_dev",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
            mediaDeliveryProvider = mediaDeliveryProvider,
            playStoreRtdnEnabled = playStoreRtdnEnabled,
            corsAllowedOrigins = corsAllowedOrigins,
        )

    private fun stagingWithLocalDefaults(): ServerConfig =
        ServerConfig(
            environment = "staging",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_dev",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
        )

    private fun validStagingConfig(
        environment: String = "staging",
        playStoreSandboxEnabled: Boolean = false,
        corsAllowedOrigins: List<String> = emptyList(),
    ): ServerConfig =
        ServerConfig(
            environment = environment,
            port = 8080,
            databaseUrl = "jdbc:postgresql:///pynapli_staging",
            databaseUser = "pynapli",
            databasePassword = "staging-db-password-abcdefghijklmnopqrstuvwxyz",
            databaseCloudSqlInstance = "pynapli-staging:us-central1:pynapli-postgres",
            redisUrl = "redis://10.10.0.8:6379",
            mediaStorageProvider = "gcs",
            mediaStorageBucket = "pynapli-staging-media",
            mediaGcsSigningServiceAccountPath = "/secure/pynapli-media-signer.json",
            notificationPushProvider = "firebase",
            firebaseProjectId = "pynapli-staging",
            adminApiToken = "adminruntimevalueabcdefghijklmnopqrstuvwxyz",
            appCheckEnforced = true,
            appCheckProjectNumber = "209241465883",
            discoveryCursorSecret = "discoverystagingruntimevalue123456789",
            activityCursorSecret = "activitystagingruntimevalue123456789",
            swipeDeckTokenSecret = "swipedeckstagingruntimevalue123456789",
            playStoreSandboxEnabled = playStoreSandboxEnabled,
            corsAllowedOrigins = corsAllowedOrigins,
        )
}
