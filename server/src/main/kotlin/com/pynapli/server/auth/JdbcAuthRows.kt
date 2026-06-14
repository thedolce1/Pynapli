package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.toEmailIdentityRecord(): EmailIdentityRecord =
    EmailIdentityRecord(
        userId = getObject("user_id", UUID::class.java),
        passwordHash =
            PasswordHash(
                hash = getString("password_hash"),
                salt = getString("password_salt"),
                iterations = getInt("password_iterations"),
            ),
        userStatus = getString("status"),
    )

internal fun ResultSet.toUserRecord(): UserRecord =
    UserRecord(
        id = getObject("id", UUID::class.java),
        status = getString("status"),
    )

internal fun ResultSet.toSessionRecord(): SessionRecord =
    SessionRecord(
        id = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        accessTokenHash = getString("access_token_hash"),
        refreshTokenHash = getString("refresh_token_hash"),
        accessExpiresAt = getTimestamp("access_expires_at").toInstant(),
        refreshExpiresAt = getTimestamp("refresh_expires_at").toInstant(),
        revokedAt = getTimestamp("revoked_at")?.toInstant(),
        userStatus = getString("status"),
    )
