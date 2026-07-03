package com.pynapli.web.auth

import com.pynapli.shared.auth.SessionStore
import com.pynapli.shared.auth.StoredSession
import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.web.platform.BrowserLocalStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Browser session persistence. Browsers expose no app-controlled keystore, so the
 * session rides `localStorage` guarded by the web shell's strict CSP (no third-party
 * scripts). Hardening candidate tracked for W6.2: keep the access token memory-only
 * and persist just the refresh token.
 */
class LocalStorageSessionStore(
    private val json: Json = PynapliJson,
) : SessionStore {
    override suspend fun currentSession(): StoredSession? {
        val value = BrowserLocalStorage.get(SESSION_KEY) ?: return null
        val payload =
            runCatching { json.decodeFromString(WebSessionPayload.serializer(), value) }
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
            WebSessionPayload(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = session.user.userId,
                userStatus = session.user.status,
            )
        BrowserLocalStorage.set(SESSION_KEY, json.encodeToString(WebSessionPayload.serializer(), payload))
    }

    override suspend fun clear() {
        BrowserLocalStorage.remove(SESSION_KEY)
    }

    private companion object {
        private const val SESSION_KEY = "pynapli.auth.session.v1"
    }
}

@Serializable
private data class WebSessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val userStatus: String,
)
