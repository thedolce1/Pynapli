package com.pynapli.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

private val DefaultAllowedOrigins = listOf(
    "https://pynapli.com",
    "https://www.pynapli.com",
    "https://web.pynapli.com",
    "http://localhost:3000",
    "http://127.0.0.1:3000"
)

fun Application.configureHTTP() {
    val allowedOrigins = (System.getenv("PYNAPLI_CORS_ALLOWED_ORIGINS") ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { DefaultAllowedOrigins }

    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader("X-Platform")
        allowHeader("X-App-Version")
        allowHeader("X-Request-Id")
        allowHeader("Idempotency-Key")

        exposeHeader("X-Request-Id")
        exposeHeader("X-RateLimit-Limit")
        exposeHeader("X-RateLimit-Remaining")
        exposeHeader("X-RateLimit-Reset")

        for (origin in allowedOrigins) {
            allowSpecifiedOrigin(origin)
        }
    }
}

private fun io.ktor.server.plugins.cors.CORSConfig.allowSpecifiedOrigin(origin: String) {
    val withoutScheme = origin.removePrefix("https://").removePrefix("http://")
    val host = withoutScheme.substringBefore(':')
    val port = withoutScheme.substringAfter(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    val schemes = if (origin.startsWith("http://")) listOf("http") else listOf("https")
    if (port != null) {
        allowHost("$host:$port", schemes = schemes)
    } else {
        allowHost(host, schemes = schemes)
    }
}
