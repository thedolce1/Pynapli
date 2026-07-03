package com.pynapli.web.settings

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.settings.AppSettings
import com.pynapli.shared.settings.AppSettingsStore
import com.pynapli.shared.settings.OnboardingProgress
import com.pynapli.shared.settings.OnboardingProgressStore
import com.pynapli.web.platform.BrowserLocalStorage
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
