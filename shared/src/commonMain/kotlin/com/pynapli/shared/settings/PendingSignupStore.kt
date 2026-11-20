package com.pynapli.shared.settings

import com.pynapli.shared.auth.SessionTokenCipher
import com.pynapli.shared.auth.createPlatformSessionTokenCipher
import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A sign-up waiting for its emailed code (ONBOARDING_AUTH_SPEC, "Email sign-up"), so an app killed
 * between sign-up and verification reopens on the code step. Never the password: only the address,
 * the server's random sign-up token (useless without the code) and when it started, so one older
 * than the server keeps it (24 hours) restarts cleanly.
 *
 * Kept apart from [OnboardingProgress] on purpose: each platform decides how private this is. The
 * phone stores encrypt it like the session tokens; the browser keeps it for the tab's session only,
 * so the next person on a shared computer never lands on someone's code step.
 */
@Serializable
data class PendingSignup(
    val email: String,
    val signupToken: String,
    val startedAtEpochMs: Long,
)

interface PendingSignupStore {
    suspend fun load(): PendingSignup?

    suspend fun save(pending: PendingSignup)

    suspend fun clear()
}

/** Process memory only: lost when the app closes (desktop dev shell, tests). */
class InMemoryPendingSignupStore(
    private var pending: PendingSignup? = null,
) : PendingSignupStore {
    override suspend fun load(): PendingSignup? = pending

    override suspend fun save(pending: PendingSignup) {
        this.pending = pending
    }

    override suspend fun clear() {
        pending = null
    }
}

/**
 * Android and iOS: one app_meta row, encrypted with the same platform cipher as the session tokens
 * (AndroidKeyStore AES-GCM on Android). A value that no longer decrypts reads as nothing pending.
 */
class SqlDelightPendingSignupStore(
    private val database: PynapliDatabase,
    private val json: Json = PynapliJson,
    private val cipher: SessionTokenCipher = createPlatformSessionTokenCipher(),
) : PendingSignupStore {
    override suspend fun load(): PendingSignup? {
        val stored =
            database.appMetaQueries
                .selectByKey(PENDING_SIGNUP_KEY)
                .executeAsOneOrNull()
                ?.value_ ?: return null
        val plain = runCatching { cipher.decrypt(stored) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(PendingSignup.serializer(), plain) }.getOrNull()
    }

    override suspend fun save(pending: PendingSignup) {
        database.appMetaQueries.upsert(
            key = PENDING_SIGNUP_KEY,
            value_ = cipher.encrypt(json.encodeToString(PendingSignup.serializer(), pending)),
            updated_at_epoch_ms = currentTimeMillis(),
        )
    }

    override suspend fun clear() {
        database.appMetaQueries.deleteByKey(PENDING_SIGNUP_KEY)
    }

    private companion object {
        private const val PENDING_SIGNUP_KEY = "onboarding.pending-signup.v1"

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}

/**
 * The address as the code step shows it after a resume: enough for the person to recognise it, not
 * enough for someone else to read it off the screen ("j•••@g•••.com").
 */
fun maskEmailAddress(email: String): String {
    val at = email.lastIndexOf('@')
    if (at <= 0 || at == email.lastIndex) return "•••"
    val local = email.substring(0, at)
    val domain = email.substring(at + 1)
    val dot = domain.lastIndexOf('.')
    val host = if (dot > 0) domain.substring(0, dot) else domain
    val tld = if (dot > 0) domain.substring(dot) else ""
    return "${local.first()}•••@${host.first()}•••$tld"
}
