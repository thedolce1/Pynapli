package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.ModerationException

const val ADMIN_ROLE_SENIOR = "senior"
const val ADMIN_ROLE_OPERATOR = "operator"

/**
 * Resolves an admin actor's role from the configured role map (`PYNAPLI_ADMIN_ROLES`). Two-tier
 * model: when no roles are configured, enforcement is OFF and every authenticated admin is treated as
 * senior (preserving the pre-roles behavior); when roles ARE configured, an unmapped actor defaults to
 * operator (least privilege).
 */
fun resolveAdminRole(
    config: ServerConfig,
    actorId: String,
): String {
    if (config.adminRoles.isEmpty()) return ADMIN_ROLE_SENIOR
    return config.adminRoles[actorId] ?: ADMIN_ROLE_OPERATOR
}

/**
 * Requires the actor to hold the senior role for a sensitive admin action (PII reveals, entitlement
 * changes, appeal decisions, reported-media removal). A no-op when no roles are configured; otherwise
 * throws `ADMIN_ROLE_REQUIRED` (mapped to 403) for operators / unmapped actors. Note: because the
 * actor id is self-declared over the shared admin token, this is separation-of-duties on top of the
 * token — the real second factor for these actions is the step-up MFA gate (E2c).
 */
fun requireSeniorAdmin(
    config: ServerConfig,
    actorId: String,
) {
    if (resolveAdminRole(config, actorId) != ADMIN_ROLE_SENIOR) {
        throw ModerationException("ADMIN_ROLE_REQUIRED")
    }
}
