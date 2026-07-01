package com.pynapli.server.plugins

import com.pynapli.server.admin.AdminRequestAuditRecorder
import com.pynapli.server.admin.adminAuditRequired
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.launch

private const val ADMIN_ACTOR_HEADER = "X-Admin-Id"

private class AdminAuditPluginConfig {
    var recorder: AdminRequestAuditRecorder? = null
}

private val AdminAuditLogging =
    createApplicationPlugin(name = "AdminAuditLogging", createConfiguration = ::AdminAuditPluginConfig) {
        val recorder = pluginConfig.recorder ?: return@createApplicationPlugin
        val scope = application
        on(ResponseSent) { call ->
            val status = call.response.status()?.value
            val actorId =
                call.request
                    .header(ADMIN_ACTOR_HEADER)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val method = call.request.httpMethod.value
            val path = call.request.path()
            if (status != null && actorId != null && adminAuditRequired(method, path, status)) {
                // Fire-and-forget: the audit trail must never delay or fail a completed admin action.
                scope.launch { recorder.record(actorId = actorId, method = method, path = path, status = status) }
            }
        }
    }

/**
 * Installs the general admin audit trail: every successful admin mutation (2xx POST/PUT/PATCH/DELETE
 * under `/v1/admin`) is recorded as an `admin_request` row, so no admin action goes unlogged even if a
 * specific handler omits its own audit write. No-op when the admin surface is disabled (null recorder).
 */
fun Application.configureAdminAudit(recorder: AdminRequestAuditRecorder?) {
    if (recorder == null) return
    install(AdminAuditLogging) { this.recorder = recorder }
}
