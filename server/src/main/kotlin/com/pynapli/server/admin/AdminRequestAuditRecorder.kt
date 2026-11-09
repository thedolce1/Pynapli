package com.pynapli.server.admin

import com.pynapli.server.db.withConnection
import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.sql.DataSource

private val MUTATING_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

/**
 * True when a completed request should be recorded as a general admin action: a successful (2xx)
 * mutating request under the admin surface. Sensitive reads already write their own reason-bearing
 * audit rows; this catch-all guarantees every admin *mutation* leaves a trail even if a handler omits
 * its own write. Pure so the contract is unit-testable without a running pipeline.
 */
internal fun adminAuditRequired(
    method: String,
    path: String,
    status: Int,
): Boolean {
    if (method.uppercase() !in MUTATING_METHODS) return false
    if (!path.startsWith("/v1/admin/")) return false
    return status in 200..299
}

/**
 * Records a request-level admin audit row (the general audit middleware). Distinct from the rich
 * domain rows (which carry reason/target): the action is `admin_request`, the target is the route,
 * and the method/path/status ride in metadata.
 */
interface AdminRequestAuditRecorder {
    suspend fun record(
        actorId: String,
        method: String,
        path: String,
        status: Int,
    )
}

internal class JdbcAdminRequestAuditRecorder(
    private val dataSource: DataSource,
) : AdminRequestAuditRecorder {
    override suspend fun record(
        actorId: String,
        method: String,
        path: String,
        status: Int,
    ) {
        val metadata =
            buildJsonObject {
                put("method", method)
                put("path", path)
                put("status", status)
            }.toString()
        dataSource.withConnection { connection ->
            connection.insertAdminAuditLog(
                actorId = actorId,
                action = "admin_request",
                targetType = "route",
                targetId = null,
                reason = null,
                metadataJson = metadata,
            )
        }
    }
}
