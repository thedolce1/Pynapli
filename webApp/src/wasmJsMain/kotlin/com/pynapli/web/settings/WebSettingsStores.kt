package com.pynapli.web.settings

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.settings.AppSettings
import com.pynapli.shared.settings.AppSettingsStore
import com.pynapli.shared.settings.LocationSharingConsent
import com.pynapli.shared.settings.LocationSharingConsentStore
import com.pynapli.shared.settings.OnboardingProgress
import com.pynapli.shared.settings.OnboardingProgressStore
import com.pynapli.shared.settings.PendingSignup
import com.pynapli.shared.settings.PendingSignupStore
import com.pynapli.shared.settings.UnderageStopStore
import com.pynapli.web.platform.BrowserLocalStorage
import com.pynapli.web.platform.BrowserSessionStorage
import kotlinx.serialization.json.Json

/** Browser twin of `SqlDelightAppSettingsStore`: one JSON blob, decode-failure falls back to defaults. */
class LocalStorageAppSettingsStore(
    private val json: Json = PynapliJson,
) : AppSettingsStore {
    override suspend fun load(): AppSettings {
        val value = BrowserLocalStorage.get(SETTINGS_KEY) ?: return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), value) }
            .getOrElse { AppSettings() }
    }

    override suspend fun save(settings: AppSettings) {
        BrowserLocalStorage.set(SETTINGS_KEY, json.encodeToString(AppSettings.serializer(), settings))
    }

    private companion object {
        private const val SETTINGS_KEY = "pynapli.app.settings.v1"
    }
}

/** Browser twin of `SqlDelightOnboardingProgressStore` (same separate-writer rationale). */
class LocalStorageOnboardingProgressStore(
    private val json: Json = PynapliJson,
) : OnboardingProgressStore {
    override suspend fun load(): OnboardingProgress {
        val value = BrowserLocalStorage.get(PROGRESS_KEY) ?: return OnboardingProgress()
        return runCatching { json.decodeFromString(OnboardingProgress.serializer(), value) }
            .getOrElse { OnboardingProgress() }
    }

    override suspend fun save(progress: OnboardingProgress) {
        BrowserLocalStorage.set(PROGRESS_KEY, json.encodeToString(OnboardingProgress.serializer(), progress))
    }

    private companion object {
        private const val PROGRESS_KEY = "pynapli.onboarding.progress.v1"
    }
}

/**
 * A sign-up waiting for its emailed code, kept for this tab's session only (`sessionStorage`), never
 * in `localStorage`: on a shared computer the next visitor must not land on someone's code step.
 */
class SessionStoragePendingSignupStore(
    private val json: Json = PynapliJson,
) : PendingSignupStore {
    override suspend fun load(): PendingSignup? {
        val value = BrowserSessionStorage.get(PENDING_SIGNUP_KEY) ?: return null
        return runCatching { json.decodeFromString(PendingSignup.serializer(), value) }.getOrNull()
    }

    override suspend fun save(pending: PendingSignup) {
        BrowserSessionStorage.set(PENDING_SIGNUP_KEY, json.encodeToString(PendingSignup.serializer(), pending))
    }

    override suspend fun clear() {
        BrowserSessionStorage.remove(PENDING_SIGNUP_KEY)
    }

    private companion object {
        private const val PENDING_SIGNUP_KEY = "pynapli.onboarding.pending-signup.v1"
    }
}

/**
 * The 18+ stop's time, kept for this tab's session only (`sessionStorage`), like the pending sign-up:
 * a refresh can't retype the birthday older, and the next visitor on a shared computer starts clean.
 */
class SessionStorageUnderageStopStore : UnderageStopStore {
    override suspend fun load(): Long? = BrowserSessionStorage.get(UNDERAGE_STOP_KEY)?.toLongOrNull()

    override suspend fun save(stoppedAtEpochMs: Long) {
        BrowserSessionStorage.set(UNDERAGE_STOP_KEY, stoppedAtEpochMs.toString())
    }

    override suspend fun clear() {
        BrowserSessionStorage.remove(UNDERAGE_STOP_KEY)
    }

    private companion object {
        private const val UNDERAGE_STOP_KEY = "pynapli.onboarding.underage-stop.v1"
    }
}

/** Browser twin of `SqlDelightLocationSharingConsentStore`: one account's opaque id, never a location. */
class LocalStorageLocationSharingConsentStore(
    private val json: Json = PynapliJson,
) : LocationSharingConsentStore {
    override suspend fun load(): LocationSharingConsent {
        val value = BrowserLocalStorage.get(CONSENT_KEY) ?: return LocationSharingConsent()
        return runCatching { json.decodeFromString(LocationSharingConsent.serializer(), value) }
            .getOrElse { LocationSharingConsent() }
    }

    override suspend fun save(consent: LocationSharingConsent) {
        BrowserLocalStorage.set(CONSENT_KEY, json.encodeToString(LocationSharingConsent.serializer(), consent))
        // v1 kept a list of up to 8 accounts' ids; nothing needs that list any more.
        BrowserLocalStorage.remove(LEGACY_LIST_KEY)
    }

    private companion object {
        private const val CONSENT_KEY = "pynapli.location.sharing-consent.v2"
        private const val LEGACY_LIST_KEY = "pynapli.location.sharing-consent.v1"
    }
}
