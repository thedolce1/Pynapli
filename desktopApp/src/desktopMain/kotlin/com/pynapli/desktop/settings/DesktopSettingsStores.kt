package com.pynapli.desktop.settings

import com.pynapli.desktop.platform.DesktopStorage
import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.settings.AppSettings
import com.pynapli.shared.settings.AppSettingsStore
import com.pynapli.shared.settings.OnboardingProgress
import com.pynapli.shared.settings.OnboardingProgressStore
import kotlinx.serialization.json.Json

/** Desktop twin of `LocalStorageAppSettingsStore`: one JSON blob, decode-failure falls back to defaults. */
class DesktopAppSettingsStore(
    private val json: Json = PynapliJson,
) : AppSettingsStore {
    override suspend fun load(): AppSettings {
        val value = DesktopStorage.get(SETTINGS_KEY) ?: return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), value) }
            .getOrElse { AppSettings() }
    }

    override suspend fun save(settings: AppSettings) {
        DesktopStorage.set(SETTINGS_KEY, json.encodeToString(AppSettings.serializer(), settings))
    }

    private companion object {
        private const val SETTINGS_KEY = "app-settings-v1"
    }
}

/** Desktop twin of `LocalStorageOnboardingProgressStore` (same separate-writer rationale). */
class DesktopOnboardingProgressStore(
    private val json: Json = PynapliJson,
) : OnboardingProgressStore {
    override suspend fun load(): OnboardingProgress {
        val value = DesktopStorage.get(PROGRESS_KEY) ?: return OnboardingProgress()
        return runCatching { json.decodeFromString(OnboardingProgress.serializer(), value) }
            .getOrElse { OnboardingProgress() }
    }

    override suspend fun save(progress: OnboardingProgress) {
        DesktopStorage.set(PROGRESS_KEY, json.encodeToString(OnboardingProgress.serializer(), progress))
    }

    private companion object {
        private const val PROGRESS_KEY = "onboarding-progress-v1"
    }
}
