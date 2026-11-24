package com.pynapli.shared.settings

import com.pynapli.shared.storage.PynapliDatabase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * When this device last stopped a sign-up at the 18+ screen (ONBOARDING_AUTH_SPEC, "Signup Flow").
 * For a day after, Create account goes straight back to that screen, so the birthday can't simply be
 * retyped older; Sign in stays available. Only a time is stored: no birthday, no address, nothing
 * that names anyone. Device-local (app storage on phones, the tab's session on the web).
 */
interface UnderageStopStore {
    /** Epoch ms of the last stop, or null. */
    suspend fun load(): Long?

    suspend fun save(stoppedAtEpochMs: Long)

    suspend fun clear()
}

/** Process memory only (desktop dev shell, tests). */
class InMemoryUnderageStopStore(
    private var stoppedAtEpochMs: Long? = null,
) : UnderageStopStore {
    override suspend fun load(): Long? = stoppedAtEpochMs

    override suspend fun save(stoppedAtEpochMs: Long) {
        this.stoppedAtEpochMs = stoppedAtEpochMs
    }

    override suspend fun clear() {
        stoppedAtEpochMs = null
    }
}

/** Android and iOS: one app_meta row holding the time (nothing personal, so not encrypted). */
class SqlDelightUnderageStopStore(
    private val database: PynapliDatabase,
) : UnderageStopStore {
    override suspend fun load(): Long? =
        database.appMetaQueries
            .selectByKey(UNDERAGE_STOP_KEY)
            .executeAsOneOrNull()
            ?.value_
            ?.toLongOrNull()

    override suspend fun save(stoppedAtEpochMs: Long) {
        database.appMetaQueries.upsert(
            key = UNDERAGE_STOP_KEY,
            value_ = stoppedAtEpochMs.toString(),
            updated_at_epoch_ms = currentTimeMillis(),
        )
    }

    override suspend fun clear() {
        database.appMetaQueries.deleteByKey(UNDERAGE_STOP_KEY)
    }

    private companion object {
        private const val UNDERAGE_STOP_KEY = "onboarding.underage-stop.v1"

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
