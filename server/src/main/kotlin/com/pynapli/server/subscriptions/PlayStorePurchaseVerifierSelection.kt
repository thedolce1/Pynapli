package com.pynapli.server.subscriptions

import com.pynapli.server.config.ServerConfig
import org.slf4j.LoggerFactory

private val selectionLogger =
    LoggerFactory.getLogger("com.pynapli.server.subscriptions.PlayStorePurchaseVerifierSelection")

private const val PRODUCTION_ENVIRONMENT = "production"

/**
 * Chooses the active [PlayStorePurchaseVerifier]:
 *
 * 1. Real [GooglePlayStorePurchaseVerifier] when production Play verification is enabled (needs
 *    service-account credentials). Always wins when configured.
 * 2. [SandboxPlayStorePurchaseVerifier] when explicitly enabled AND not running in production —
 *    lets the real submit -> verify -> grant path be exercised end-to-end without Google creds.
 *    Refused in production as a backstop even if the flag is somehow set there.
 * 3. [DisabledPlayStorePurchaseVerifier] otherwise (purchases park pending until real verification
 *    reconciles them).
 */
internal fun selectPlayStorePurchaseVerifier(config: ServerConfig): PlayStorePurchaseVerifier =
    when {
        config.playStoreVerificationEnabled -> GooglePlayStorePurchaseVerifier.fromConfig(config)
        config.playStoreSandboxEnabled && config.environment != PRODUCTION_ENVIRONMENT -> {
            selectionLogger.warn(
                "Play Store SANDBOX verifier is active (environment={}). Sandbox-prefixed purchase " +
                    "tokens grant entitlement WITHOUT Google verification; this must never run in production.",
                config.environment,
            )
            SandboxPlayStorePurchaseVerifier()
        }
        else -> DisabledPlayStorePurchaseVerifier
    }
