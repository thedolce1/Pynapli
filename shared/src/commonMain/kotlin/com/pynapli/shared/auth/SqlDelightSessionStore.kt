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
    private val json: Json = PynapliJson,
    private val cipher: SessionTokenCipher = createPlatformSessionTokenCipher(),
) : SessionStore {
    private val codec = SessionPayloadCodec(cipher, json)

    override suspend fun currentSession(): StoredSession? {
        val value =
            database.appMetaQueries
                .selectByKey(SESSION_KEY)
                .executeAsOneOrNull()
                ?.value_ ?: return null
        val decoded = codec.decode(value)
        val payload = decoded.payload ?: return null
        if (decoded.needsEncryptionUpgrade) {
            // Pre-encryption plaintext row: upgrade on first read so the
            // plaintext-at-rest window closes immediately, not at the next save.
            runCatching { persist(codec.encode(payload)) }
        }
        return payload.toStoredSession()
    }

    override suspend fun save(session: AuthSessionDto) {
        val payload =
            StoredSessionPayload(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = session.user.userId,
                userStatus = session.user.status,
            )
        persist(codec.encode(payload))
    }

    override suspend fun clear() {
        database.appMetaQueries.deleteByKey(SESSION_KEY)
        // Order matters only for tidiness, but the call itself is load-bearing on iOS: the row is a
        // Keychain reference, so dropping it alone would orphan the token on the device.
        cipher.forgetStoredSecret()
    }

    private fun persist(value: String) {
        database.appMetaQueries.upsert(
            key = SESSION_KEY,
            value_ = value,
            updated_at_epoch_ms = currentTimeMillis(),
        )
    }

    private fun StoredSessionPayload.toStoredSession(): StoredSession =
        StoredSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            userStatus = userStatus,
        )

    companion object {
        private const val SESSION_KEY = "auth.session.v1"

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}

/**
 * Serializes the session payload and applies the platform [SessionTokenCipher] at
 * rest (ONBOARDING_AUTH_SPEC "Secure token storage"). Decoding accepts both
 * encrypted values and pre-encryption plaintext rows; an encrypted value that no
 * longer decrypts (key invalidated, tampering) decodes to null, which callers
 * surface as a signed-out session.
 */
internal class SessionPayloadCodec(
    private val cipher: SessionTokenCipher,
    private val json: Json,
) {
    internal data class Decoded(
        val payload: StoredSessionPayload?,
        val needsEncryptionUpgrade: Boolean,
    )

    fun encode(payload: StoredSessionPayload): String = cipher.encrypt(json.encodeToString(StoredSessionPayload.serializer(), payload))

    fun decode(value: String): Decoded {
        val decrypted = cipher.decrypt(value)
        val payloadJson = decrypted ?: value
        val payload =
            runCatching {
                json.decodeFromString(StoredSessionPayload.serializer(), payloadJson)
            }.getOrNull()
        return Decoded(
            payload = payload,
            needsEncryptionUpgrade = payload != null && decrypted == null,
        )
    }
}

@Serializable
internal data class StoredSessionPayload(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val userStatus: String,
)
