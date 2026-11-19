package com.pynapli.shared.settings

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Device-local onboarding flow state (ONBOARDING_AUTH_SPEC). Deliberately NOT part
 * of [AppSettings]: the server-backed settings repository read-merge-saves that
 * whole blob on sign-in, and onboarding progress written concurrently (signup
 * completes at the same moment the settings sync starts) must never be clobbered
 * by that round-trip. Separate key, separate writer.
 */
@Serializable
data class OnboardingProgress(
    // True default keeps every existing signed-in install (and plain sign-ins) out
    // of the stepper — signup explicitly flips it false at account creation.
    val completed: Boolean = true,
    // Pins the resume to the account that started signup, so a different sign-in
    // on the same device never inherits the stepper.
    val userId: String? = null,
    val photoNudgeDismissed: Boolean = false,
    val photoNudgeSessionsSeen: Int = 0,
    // A sign-up waiting for its emailed code (ONBOARDING_AUTH_SPEC, "Membership privacy"), so an app
    // killed between sign-up and verification reopens on the code step for that address. Never the
    // password: only the address, the server's random sign-up token (useless without the code) and
    // when it started, so a sign-up older than the server keeps it (24 hours) restarts cleanly.
    val pendingSignupEmail: String? = null,
    val pendingSignupToken: String? = null,
    val pendingSignupStartedAtEpochMs: Long? = null,
)

interface OnboardingProgressStore {
    suspend fun load(): OnboardingProgress

    suspend fun save(progress: OnboardingProgress)
}

class SqlDelightOnboardingProgressStore(
    private val database: PynapliDatabase,
    private val json: Json = PynapliJson,
) : OnboardingProgressStore {
    override suspend fun load(): OnboardingProgress {
        val value =
            database.appMetaQueries
                .selectByKey(ONBOARDING_PROGRESS_KEY)
                .executeAsOneOrNull()
                ?.value_
                ?: return OnboardingProgress()
        return runCatching { json.decodeFromString(OnboardingProgress.serializer(), value) }
            .getOrElse { OnboardingProgress() }
    }

    override suspend fun save(progress: OnboardingProgress) {
        database.appMetaQueries.upsert(
            key = ONBOARDING_PROGRESS_KEY,
            value_ = json.encodeToString(OnboardingProgress.serializer(), progress),
            updated_at_epoch_ms = currentTimeMillis(),
        )
    }

    private companion object {
        private const val ONBOARDING_PROGRESS_KEY = "onboarding.progress.v1"

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
