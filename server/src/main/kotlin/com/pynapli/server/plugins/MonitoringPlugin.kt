package com.pynapli.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level
import java.util.UUID

/** The request id header: honoured when a client sends a well-formed one, generated otherwise. */
const val REQUEST_ID_HEADER = "X-Request-Id"

private val REQUEST_ID_FORMAT = Regex("^[A-Za-z0-9._-]{8,128}$")

/** This call's request id (the same one sent back in [REQUEST_ID_HEADER] and logged as `requestId`). */
fun ApplicationCall.requestIdOrUnknown(): String = callId ?: "unknown"

fun Application.configureMonitoring() {
    // Every response carries X-Request-Id and every log line written while handling it carries the
    // same id (MDC `requestId`), so a user-reported error can be matched to its server logs.
    install(CallId) {
        retrieveFromHeader(REQUEST_ID_HEADER)
        verify { it.matches(REQUEST_ID_FORMAT) }
        generate { UUID.randomUUID().toString() }
        replyToHeader(REQUEST_ID_HEADER)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
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
