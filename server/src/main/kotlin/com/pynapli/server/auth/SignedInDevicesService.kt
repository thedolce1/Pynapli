package com.pynapli.server.auth

import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.db.withConnection
import com.pynapli.shared.network.dto.SignedInDeviceDto
import com.pynapli.shared.network.dto.SignedInDevicesDto
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * "Where you're signed in": the account's live sessions, and signing one (or all the others) out.
 * A lost or shared phone is a real risk on a lifestyle app, so the person must be able to end a
 * session they don't recognise without changing their password.
 */
class SignedInDevicesService(
    private val repository: SignedInDevicesRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val onSessionsRevoked: (UUID) -> Unit = {},
) {
    suspend fun list(
        userId: UUID,
        accessToken: String,
    ): SignedInDevicesDto {
        val currentHash = Sha256.hash(accessToken)
        val devices =
            repository.liveSessions(userId, clock.instant()).map { session ->
                SignedInDeviceDto(
                    sessionId = session.id.toString(),
                    platform = session.platform,
                    appVersion = session.appVersion,
                    signedInAt = session.createdAt.toString(),
                    lastActiveAt = session.lastSeenAt.toString(),
                    current = session.accessTokenHash == currentHash,
                )
            }
        // This device first, then most recently active (the repository already orders by activity).
        return SignedInDevicesDto(devices = devices.sortedByDescending { it.current })
    }

    /** Signs out one of the account's other sessions. This device signs out with Log out instead. */
    suspend fun signOut(
        userId: UUID,
        accessToken: String,
        sessionId: String,
    ) {
        val id = runCatching { UUID.fromString(sessionId) }.getOrNull() ?: throw AuthException("SESSION_NOT_FOUND")
        when (repository.revoke(userId, id, keepAccessTokenHash = Sha256.hash(accessToken))) {
            RevokeResult.Revoked -> onSessionsRevoked(userId)
            RevokeResult.IsCurrent -> throw AuthException("CANNOT_SIGN_OUT_CURRENT_SESSION")
            RevokeResult.NotFound -> throw AuthException("SESSION_NOT_FOUND")
        }
    }

    /** Signs out every session except this one. Returns how many ended. */
    suspend fun signOutOthers(
        userId: UUID,
        accessToken: String,
    ): Int {
        val count = repository.revokeOthers(userId, keepAccessTokenHash = Sha256.hash(accessToken))
        if (count > 0) onSessionsRevoked(userId)
        return count
    }
}

interface SignedInDevicesRepository {
    suspend fun liveSessions(
        userId: UUID,
        now: Instant,
    ): List<LiveSessionRecord>

    suspend fun revoke(
        userId: UUID,
        sessionId: UUID,
        keepAccessTokenHash: String,
    ): RevokeResult

    suspend fun revokeOthers(
        userId: UUID,
        keepAccessTokenHash: String,
    ): Int
}

data class LiveSessionRecord(
    val id: UUID,
    val accessTokenHash: String,
    val platform: String?,
    val appVersion: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)

enum class RevokeResult { Revoked, IsCurrent, NotFound }

class JdbcSignedInDevicesRepository(
    private val dataSource: DataSource,
) : SignedInDevicesRepository {
    override suspend fun liveSessions(
        userId: UUID,
        now: Instant,
    ): List<LiveSessionRecord> =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, access_token_hash, platform, app_version, created_at, last_seen_at
                    FROM user_sessions
                    WHERE user_id = ?
                      AND revoked_at IS NULL
                      AND refresh_expires_at > ?
                    ORDER BY last_seen_at DESC
                    LIMIT 50
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    LiveSessionRecord(
                                        id = rs.getObject("id", UUID::class.java),
                                        accessTokenHash = rs.getString("access_token_hash"),
                                        platform = rs.getString("platform"),
                                        appVersion = rs.getString("app_version"),
                                        createdAt = rs.getTimestamp("created_at").toInstant(),
                                        lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override suspend fun revoke(
        userId: UUID,
        sessionId: UUID,
        keepAccessTokenHash: String,
    ): RevokeResult =
        dataSource.withConnection { connection ->
            val sessionHash =
                connection
                    .prepareStatement("SELECT access_token_hash FROM user_sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL")
                    .use { statement ->
                        statement.setObject(1, sessionId)
                        statement.setObject(2, userId)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    } ?: return@withConnection RevokeResult.NotFound
            if (sessionHash == keepAccessTokenHash) return@withConnection RevokeResult.IsCurrent
            val updated =
                connection
                    .prepareStatement(
                        "UPDATE user_sessions SET revoked_at = now(), $CLEAR_REFRESH_REPLAY_SQL " +
                            "WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                    ).use { statement ->
                        statement.setObject(1, sessionId)
                        statement.setObject(2, userId)
                        statement.executeUpdate()
                    }
            if (updated > 0) RevokeResult.Revoked else RevokeResult.NotFound
        }

    override suspend fun revokeOthers(
        userId: UUID,
        keepAccessTokenHash: String,
    ): Int =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    "UPDATE user_sessions SET revoked_at = now(), $CLEAR_REFRESH_REPLAY_SQL " +
                        "WHERE user_id = ? AND revoked_at IS NULL AND access_token_hash <> ?",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, keepAccessTokenHash)
                    statement.executeUpdate()
                }
        }
}
