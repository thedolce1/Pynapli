package com.pynapli.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        // Redaction (CLAUDE.md "do not log" list): emit only "<status>: <METHOD> <path>" and never the
        // query string. The default CallLogging format logs the full request URI including the query,
        // which can carry opaque discovery/swipe cursors and filter params; `request.path()` is the
        // query-less path. CallLogging never logs headers, so bearer tokens are unaffected. Path
        // segments are resource IDs only — never bodies, exact location, media URLs, or tags.
        format { call ->
            monitoringLogLine(
                method = call.request.httpMethod.value,
                path = call.request.path(),
                status = call.response.status()?.value,
            )
        }
    }
}

/** Pure, query-string-free request log line. Extracted so the redaction contract is unit-testable. */
internal fun monitoringLogLine(
    method: String,
    path: String,
    status: Int?,
): String = "${status ?: "-"}: $method $path"
