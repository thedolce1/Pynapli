package com.pynapli.server.plugins

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.safety.AppCheckException
import com.pynapli.server.safety.AppCheckVerifier
import com.pynapli.server.safety.DisabledAppCheckVerifier
import com.pynapli.server.safety.HeaderPresenceAppCheckVerifier
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

const val APP_CHECK_HEADER = "X-Firebase-AppCheck"

private val MUTATING_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)

// Requests that do not originate from an app instance and so cannot carry an App Check token:
// process health checks, the Google Pub/Sub Play RTDN webhook, and the identity-provider age
// callback. Matched by exact path or as a path-segment prefix.
private val EXEMPT_PATH_PREFIXES = listOf("/health", "/v1/webhooks", "/v1/verification/callback")

/**
 * The App Check boundary applies to state-changing requests (POST/PUT/PATCH/DELETE) except those
 * from non-app origins. Pure so the contract is unit-testable without a running pipeline.
 */
internal fun appCheckRequired(
    method: HttpMethod,
    path: String,
): Boolean {
    if (method !in MUTATING_METHODS) return false
    if (EXEMPT_PATH_PREFIXES.any { path == it || path.startsWith("$it/") }) return false
    return true
}

private class AppCheckPluginConfig {
    var verifier: AppCheckVerifier = DisabledAppCheckVerifier
}

private val AppCheckEnforcement =
    createApplicationPlugin(name = "AppCheckEnforcement", createConfiguration = ::AppCheckPluginConfig) {
        val verifier = pluginConfig.verifier
        onCall { call ->
            val request = call.request
            if (appCheckRequired(request.httpMethod, request.path()) &&
                !verifier.verify(request.header(APP_CHECK_HEADER))
            ) {
                throw AppCheckException("APP_CHECK_REQUIRED")
            }
        }
    }

/** Installs the App Check boundary only when enforced; a no-op in local/dev/test otherwise. */
fun Application.configureAppCheck(
    enforced: Boolean,
    verifier: AppCheckVerifier,
) {
    if (!enforced) return
    install(AppCheckEnforcement) { this.verifier = verifier }
}

/**
 * Picks the verifier for the current config. Real cryptographic verification (App Check JWT against
 * Google's public keys) is a creds-gated Phase H step; until then enforcement requires only that the
 * attestation header is present.
 */
fun selectAppCheckVerifier(config: ServerConfig): AppCheckVerifier = if (config.appCheckEnforced) HeaderPresenceAppCheckVerifier else DisabledAppCheckVerifier
