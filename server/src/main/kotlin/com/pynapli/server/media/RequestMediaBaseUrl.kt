package com.pynapli.server.media

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.port
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * Per-request base URL (`scheme://host[:port]` the client actually connected to) used to build LOCAL
 * dev media URLs. Bridged into the coroutine context via [asContextElement] so the singleton
 * [LocalMediaAssetUrlResolver] can read it without threading the request through every service that
 * builds a media DTO. Null outside a request (background jobs / tests) — the resolver then falls
 * back to its static configured base.
 */
internal val requestMediaBaseUrl = ThreadLocal<String?>()

/**
 * Makes local dev media URLs use the host the client addressed (Android emulator `10.0.2.2`, a
 * physical device on the LAN, `localhost`, a demo tunnel, …) instead of a single fixed
 * `PYNAPLI_MEDIA_UPLOAD_BASE_URL`, so images load on whatever target is hitting the server without
 * env juggling. No effect on the GCS / CDN resolvers (they sign absolute URLs independent of the
 * request host).
 *
 * Honors `X-Forwarded-Proto` / `X-Forwarded-Host` so the URLs stay loadable behind a TLS-terminating
 * proxy (cloudflared demo tunnel, Cloud Run), where the connection the server sees is plain HTTP on a
 * backend port: a cleartext `http://` media URL would be refused by Android/browser clients. The
 * headers are honored only here — for the media base echoed back to the requesting client — not
 * globally (a spoofed header must not alter origin data used in logs or risk signals).
 */
fun Application.configureRequestMediaBaseUrl() {
    intercept(ApplicationCallPipeline.Plugins) {
        val request = call.request
        val base =
            mediaBaseUrl(
                originScheme = request.origin.scheme,
                hostHeaderHost = request.host(),
                hostHeaderPort = request.port(),
                forwardedProto = request.header("X-Forwarded-Proto"),
                forwardedHost = request.header("X-Forwarded-Host"),
            )
        withContext(requestMediaBaseUrl.asContextElement(value = base)) {
            proceed()
        }
    }
}

/** First value of a potentially comma-separated forwarded header (proxy chains append). */
private fun String?.firstForwardedValue(): String? =
    this
        ?.split(',')
        ?.first()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun mediaBaseUrl(
    originScheme: String,
    hostHeaderHost: String,
    hostHeaderPort: Int,
    forwardedProto: String? = null,
    forwardedHost: String? = null,
): String {
    val proxiedScheme = forwardedProto.firstForwardedValue()
    val scheme = proxiedScheme ?: originScheme
    val host: String
    val port: Int?
    val forwarded = forwardedHost.firstForwardedValue()
    if (forwarded != null) {
        host = forwarded.substringBefore(':')
        port = forwarded.substringAfter(':', missingDelimiterValue = "").toIntOrNull()
    } else {
        host = hostHeaderHost
        // Behind a proxy that only forwards the scheme, the Host-header port is the backend's
        // plain-HTTP port, not the public one — the public URL uses the scheme's default.
        port = if (proxiedScheme != null) null else hostHeaderPort
    }
    val defaultPort = if (scheme.equals("https", ignoreCase = true)) 443 else 80
    val portSuffix = if (port == null || port == defaultPort) "" else ":$port"
    return "$scheme://$host$portSuffix"
}
