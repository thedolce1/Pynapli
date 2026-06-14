package com.pynapli.server.auth

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class AuthAccessSessionCache(
    private val ttl: Duration,
    private val maxSize: Int,
) {
    private val sessions = ConcurrentHashMap<String, CachedAccessSession>()

    fun get(
        accessTokenHash: String,
        now: Instant,
    ): UserRecord? {
        sessions[accessTokenHash]?.let { cached ->
            if (cached.expiresAt.isAfter(now)) return cached.user
            sessions.remove(accessTokenHash, cached)
        }
        return null
    }

    fun put(
        accessTokenHash: String,
        user: UserRecord,
        sessionExpiresAt: Instant,
        now: Instant,
    ) {
        if (ttl.isZero || ttl.isNegative || maxSize <= 0) return
        if (sessions.size >= maxSize) {
            sessions.entries.removeIf { (_, value) -> !value.expiresAt.isAfter(now) }
            if (sessions.size >= maxSize) sessions.clear()
        }
        val cacheExpiresAt = minOf(now.plus(ttl), sessionExpiresAt)
        if (cacheExpiresAt.isAfter(now)) {
            sessions[accessTokenHash] = CachedAccessSession(user = user, expiresAt = cacheExpiresAt)
        }
    }

    fun remove(accessTokenHash: String) {
        sessions.remove(accessTokenHash)
    }

    fun removeUser(userId: UUID) {
        sessions.entries.removeIf { (_, cached) -> cached.user.id == userId }
    }
}

private data class CachedAccessSession(
    val user: UserRecord,
    val expiresAt: Instant,
)
