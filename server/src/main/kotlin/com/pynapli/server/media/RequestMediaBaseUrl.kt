package com.pynapli.server.media

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.port
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * Per-request base URL (`scheme://host:port` the client actually connected to) used to build LOCAL
 * dev media URLs. Bridged into the coroutine context via [asContextElement] so the singleton
 * [LocalMediaAssetUrlResolver] can read it without threading the request through every service that
 * builds a media DTO. Null outside a request (background jobs / tests) — the resolver then falls
 * back to its static configured base.
 */
internal val requestMediaBaseUrl = ThreadLocal<String?>()

/**
 * Makes local dev media URLs use the host the client addressed (Android emulator `10.0.2.2`, a
 * physical device on the LAN, `localhost`, …) instead of a single fixed
 * `PYNAPLI_MEDIA_UPLOAD_BASE_URL`, so images load on whatever target is hitting the server without
 * env juggling. No effect on the GCS / CDN resolvers (they sign absolute URLs independent of the
 * request host).
 */
fun Application.configureRequestMediaBaseUrl() {
    intercept(ApplicationCallPipeline.Plugins) {
        val request = call.request
        val base = "${request.origin.scheme}://${request.host()}:${request.port()}"
        withContext(requestMediaBaseUrl.asContextElement(value = base)) {
            proceed()
        }
    }
}
