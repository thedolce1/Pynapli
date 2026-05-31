package com.pynapli.shared.settings

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class SqlDelightAppSettingsStore(
    private val database: PynapliDatabase,
    private val json: Json = PynapliJson,
) : AppSettingsStore {
    override suspend fun load(): AppSettings {
        val value =
            database.appMetaQueries
                .selectByKey(APP_SETTINGS_KEY)
                .executeAsOneOrNull()
                ?.value_
                ?: return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), value).normalized() }
            .getOrElse { AppSettings() }
    }

    override suspend fun save(settings: AppSettings) {
        database.appMetaQueries.upsert(
            key = APP_SETTINGS_KEY,
            value_ = json.encodeToString(AppSettings.serializer(), settings.normalized()),
            updated_at_epoch_ms = currentTimeMillis(),
        )
    }

    private fun AppSettings.normalized(): AppSettings =
        copy(
            themeMode = themeMode.takeIf { it in themeModes } ?: AppSettings.THEME_DARK,
            distanceUnit = distanceUnit.takeIf { it in distanceUnits } ?: AppSettings.UNIT_METRIC,
            notificationDetail =
                notificationDetail.takeIf { it in notificationDetails }
                    ?: AppSettings.NOTIFICATION_DISCREET,
            quietHoursStartMinute = quietHoursStartMinute.normalizedMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
            quietHoursEndMinute = quietHoursEndMinute.normalizedMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE),
            quietHoursTimezone =
                quietHoursTimezone.trim().takeIf { it.isNotEmpty() }
                    ?: AppSettings.DEFAULT_QUIET_HOURS_TIMEZONE,
        )

    private fun Int.normalizedMinute(defaultValue: Int): Int = takeIf { it in 0..1439 } ?: defaultValue

    companion object {
        private const val APP_SETTINGS_KEY = "app.settings.v1"

        private val themeModes =
            setOf(
                AppSettings.THEME_DARK,
                AppSettings.THEME_LIGHT,
                AppSettings.THEME_SYSTEM,
            )
        private val distanceUnits = setOf(AppSettings.UNIT_METRIC, AppSettings.UNIT_IMPERIAL)
        private val notificationDetails =
            setOf(
                AppSettings.NOTIFICATION_DISCREET,
                AppSettings.NOTIFICATION_STANDARD,
            )

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
