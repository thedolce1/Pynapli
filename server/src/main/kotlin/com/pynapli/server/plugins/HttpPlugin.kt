package com.pynapli.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
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
        allowedOrigins.forEach { origin ->
            val scheme = origin.substringBefore("://", missingDelimiterValue = "")
            val host = origin.substringAfter("://", missingDelimiterValue = "")
            if (scheme.isNotEmpty() && host.isNotEmpty()) {
                allowHost(host, schemes = listOf(scheme))
            }
        }
    }
}
