package com.pynapli.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

/**
 * CORS for browser clients (the web PWA). Native mobile clients are unaffected — CORS
 * only gates browser-originated requests. Origins are full `scheme://host[:port]`
 * strings from [com.pynapli.server.config.ServerConfig.corsAllowedOrigins]; anything
 * unparseable is ignored rather than widening access.
 */
fun Application.configureHTTP(allowedOrigins: List<String> = emptyList()) {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // The shared client attaches this on attested requests; browsers need it allowlisted
        // for the CORS preflight or the whole request is rejected.
        allowHeader(APP_CHECK_HEADER)
        allowHeader(REQUEST_ID_HEADER)
        exposeHeader(REQUEST_ID_HEADER)
        allowedOrigins.forEach { origin ->
            val scheme = origin.substringBefore("://", missingDelimiterValue = "")
            val host = origin.substringAfter("://", missingDelimiterValue = "")
            if (scheme.isNotEmpty() && host.isNotEmpty()) {
                allowHost(host, schemes = listOf(scheme))
            }
        }
    }
}

/**
 * Baseline security headers on every response. The API serves JSON only, so the browser may not
 * sniff types, frame it, or send referrers. HSTS is set outside local (local runs plain HTTP).
 */
fun Application.configureSecurityHeaders(environment: String) {
    val sendHsts = environment != "local"
    install(
        createApplicationPlugin("PynapliSecurityHeaders") {
            onCall { call ->
                call.response.headers.append("X-Content-Type-Options", "nosniff", safeOnly = false)
                call.response.headers.append("X-Frame-Options", "DENY", safeOnly = false)
                call.response.headers.append("Referrer-Policy", "no-referrer", safeOnly = false)
                if (sendHsts) {
                    call.response.headers.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains", safeOnly = false)
                }
            }
        },
    )
}
