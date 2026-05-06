package com.pynapli.shared.settings

interface AppSettingsStore {
    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}
