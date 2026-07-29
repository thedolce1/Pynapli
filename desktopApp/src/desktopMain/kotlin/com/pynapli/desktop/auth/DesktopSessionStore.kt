package com.pynapli.desktop.auth

import com.pynapli.desktop.platform.DesktopStorage
import com.pynapli.shared.auth.SessionStore
import com.pynapli.shared.auth.StoredSession
import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.AuthSessionDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Desktop session persistence — the JVM twin of `LocalStorageSessionStore`.
 *
 * Tokens are stored in PLAINTEXT on disk. Android encrypts them with a Keystore-backed cipher and
 * `scripts/android-secure-token-qa.ps1` asserts the on-disk value is unreadable; there is no
 * equivalent app-controlled keystore here, and `SessionTokenCipher.jvm.kt` is already a documented
 * pass-through for the same reason. That is acceptable only because this shell is dev-only and
 * refuses to start against a non-local API (see `DesktopRuntimeConfig`) — it must never be the
 * basis of a shipped desktop build without solving key storage first.
 */
class DesktopSessionStore(
    private val json: Json = PynapliJson,
) : SessionStore {
    override suspend fun currentSession(): StoredSession? {
        val value = DesktopStorage.get(SESSION_KEY) ?: return null
        val payload =
            runCatching { json.decodeFromString(DesktopSessionPayload.serializer(), value) }
                .getOrNull() ?: return null
        return StoredSession(
            accessToken = payload.accessToken,
            refreshToken = payload.refreshToken,
            userId = payload.userId,
            userStatus = payload.userStatus,
        )
    }

    override suspend fun save(session: AuthSessionDto) {
        val payload =
            DesktopSessionPayload(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = session.user.userId,
                userStatus = session.user.status,
            )
        DesktopStorage.set(SESSION_KEY, json.encodeToString(DesktopSessionPayload.serializer(), payload))
    }

    override suspend fun clear() {
        DesktopStorage.remove(SESSION_KEY)
    }

    private companion object {
        private const val SESSION_KEY = "auth-session-v1"
    }
}

@Serializable
private data class DesktopSessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val userStatus: String,
)
