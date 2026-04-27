package com.pynapli.shared.auth

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class SqlDelightSessionStore(
    private val database: PynapliDatabase,
    private val json: Json = PynapliJson
) : SessionStore {
    override suspend fun currentSession(): StoredSession? {
        val value = database.appMetaQueries.selectByKey(SESSION_KEY).executeAsOneOrNull()?.value_ ?: return null
        return runCatching {
            json.decodeFromString<StoredSessionPayload>(value).toStoredSession()
        }.getOrNull()
    }

    override suspend fun save(session: AuthSessionDto) {
        val payload = StoredSessionPayload(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            userId = session.user.userId,
            userStatus = session.user.status
        )
        database.appMetaQueries.upsert(
            key = SESSION_KEY,
            value_ = json.encodeToString(StoredSessionPayload.serializer(), payload),
            updated_at_epoch_ms = currentTimeMillis()
        )
    }

    override suspend fun clear() {
        database.appMetaQueries.deleteByKey(SESSION_KEY)
    }

    private fun StoredSessionPayload.toStoredSession(): StoredSession = StoredSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        userId = userId,
        userStatus = userStatus
    )

    companion object {
        private const val SESSION_KEY = "auth.session.v1"

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}

@Serializable
private data class StoredSessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val userStatus: String
)
