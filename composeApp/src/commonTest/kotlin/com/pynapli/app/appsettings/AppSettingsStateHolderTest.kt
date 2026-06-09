package com.pynapli.app.appsettings

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsStateHolderTest {
    @Test
    fun saveDraftPersistsLocalSettings() =
        runBlocking {
            val repository = InMemoryAppSettingsRepository()
            val stateHolder = AppSettingsStateHolder(repository)

            stateHolder.load()
            stateHolder.updateThemeMode(AppSettings.THEME_LIGHT)
            stateHolder.updateDistanceUnit(AppSettings.UNIT_IMPERIAL)
            stateHolder.updateNotificationDetail(AppSettings.NOTIFICATION_STANDARD)
            stateHolder.updateActivityNotifications(false)
            stateHolder.updateQuietHoursEnabled(true)
            stateHolder.updateQuietHoursWindow(23 * 60, 7 * 60)

            val saved = stateHolder.saveDraft()

            assertTrue(saved)
            assertEquals(AppSettings.THEME_LIGHT, stateHolder.state.value.settings.themeMode)
            assertEquals(AppSettings.UNIT_IMPERIAL, stateHolder.state.value.settings.distanceUnit)
            assertEquals(AppSettings.NOTIFICATION_STANDARD, stateHolder.state.value.settings.notificationDetail)
            assertEquals(false, stateHolder.state.value.settings.activityNotifications)
            assertEquals(true, stateHolder.state.value.settings.quietHoursEnabled)
            assertEquals(23 * 60, stateHolder.state.value.settings.quietHoursStartMinute)
            assertEquals(7 * 60, stateHolder.state.value.settings.quietHoursEndMinute)
            assertEquals(stateHolder.state.value.settings, repository.settings)
        }

    @Test
    fun updateDiscreetIconSetsDraftFlagAndRoundTripsThroughSettings() =
        runBlocking {
            val repository = InMemoryAppSettingsRepository()
            val stateHolder = AppSettingsStateHolder(repository)

            stateHolder.load()
            assertEquals(false, stateHolder.state.value.draft.discreetIconEnabled)

            stateHolder.updateDiscreetIcon(true)
            assertEquals(true, stateHolder.state.value.draft.discreetIconEnabled)

            // Round-trips through toSettings()/normalized() without losing the flag.
            assertEquals(
                true,
                stateHolder.state.value.draft
                    .toSettings()
                    .discreetIconEnabled,
            )
            assertEquals(
                true,
                stateHolder.state.value.draft
                    .toSettings()
                    .normalized()
                    .discreetIconEnabled,
            )

            val saved = stateHolder.saveDraft()
            assertTrue(saved)
            assertEquals(true, stateHolder.state.value.settings.discreetIconEnabled)
            assertEquals(true, repository.settings.discreetIconEnabled)
        }

    @Test
    fun serverBackedRepositoryMergesNotificationPreferencesOnLoad() =
        runBlocking {
            val localRepository =
                InMemoryAppSettingsRepository(
                    AppSettings(themeMode = AppSettings.THEME_LIGHT, distanceUnit = AppSettings.UNIT_IMPERIAL),
                )
            val gateway =
                FakeNotificationPreferencesGateway(
                    loadResult =
                        AppResult.Success(
                            notificationPreferences(
                                detailLevel = AppSettings.NOTIFICATION_STANDARD,
                                messagesEnabled = false,
                                matchesEnabled = true,
                                activityEnabled = false,
                                quietHoursEnabled = true,
                                quietHoursStartMinute = 23 * 60,
                                quietHoursEndMinute = 7 * 60,
                                quietHoursTimezone = "America/Toronto",
                            ),
                        ),
                )
            val repository = ServerBackedAppSettingsRepository(localRepository, gateway)

            val settings = repository.load()

            assertEquals(AppSettings.THEME_LIGHT, settings.themeMode)
            assertEquals(AppSettings.UNIT_IMPERIAL, settings.distanceUnit)
            assertEquals(AppSettings.NOTIFICATION_STANDARD, settings.notificationDetail)
            assertEquals(false, settings.messageNotifications)
            assertEquals(true, settings.matchNotifications)
            assertEquals(false, settings.activityNotifications)
            assertEquals(true, settings.quietHoursEnabled)
            assertEquals(23 * 60, settings.quietHoursStartMinute)
            assertEquals(7 * 60, settings.quietHoursEndMinute)
            assertEquals("America/Toronto", settings.quietHoursTimezone)
            assertEquals(settings, localRepository.settings)
        }

    @Test
    fun serverBackedRepositoryStartsIndependentLocalAndRemoteLoadsTogether() =
        runBlocking {
            val localStarted = CompletableDeferred<Unit>()
            val remoteStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val localRepository =
                InMemoryAppSettingsRepository(
                    beforeLoad = {
                        localStarted.complete(Unit)
                        release.await()
                    },
                )
            val gateway =
                FakeNotificationPreferencesGateway(
                    beforeLoad = {
                        remoteStarted.complete(Unit)
                        release.await()
                    },
                )
            val repository = ServerBackedAppSettingsRepository(localRepository, gateway)

            val load = async { repository.load() }

            withTimeout(1_000) {
                localStarted.await()
                remoteStarted.await()
            }
            release.complete(Unit)
            load.await()

            assertEquals(1, gateway.loadCount)
        }

    @Test
    fun serverBackedRepositoryMapsEachNotificationCategoryIndependentlyOnLoad() =
        runBlocking {
            val localRepository = InMemoryAppSettingsRepository()
            val gateway =
                FakeNotificationPreferencesGateway(
                    loadResult =
                        AppResult.Success(
                            notificationPreferences(
                                activityEnabled = true,
                                flirtsEnabled = false,
                                albumRequestsEnabled = true,
                                partnerInvitesEnabled = false,
                                systemSafetyEnabled = false,
                            ),
                        ),
                )
            val repository = ServerBackedAppSettingsRepository(localRepository, gateway)

            val settings = repository.load()

            // flirtsEnabled=false while activityEnabled=true must not collapse together.
            assertEquals(true, settings.activityNotifications)
            assertEquals(false, settings.flirtNotifications)
            assertEquals(true, settings.albumRequestNotifications)
            assertEquals(false, settings.partnerInviteNotifications)
            assertEquals(false, settings.systemSafetyNotifications)
        }

    @Test
    fun serverBackedRepositoryKeepsLocalSettingsWhenServerUnavailable() =
        runBlocking {
            val local = AppSettings(notificationDetail = AppSettings.NOTIFICATION_STANDARD)
            val localRepository = InMemoryAppSettingsRepository(local)
            val gateway =
                FakeNotificationPreferencesGateway(
                    loadResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline")),
                )
            val repository = ServerBackedAppSettingsRepository(localRepository, gateway)

            assertEquals(local, repository.load())
        }

    @Test
    fun serverBackedRepositoryDoesNotSyncWhenRemoteSyncDisabled() =
        runBlocking {
            val local = AppSettings(notificationDetail = AppSettings.NOTIFICATION_STANDARD)
            val localRepository = InMemoryAppSettingsRepository(local)
            val gateway = FakeNotificationPreferencesGateway()
            val repository =
                ServerBackedAppSettingsRepository(
                    localRepository = localRepository,
                    notificationPreferencesGateway = gateway,
                    timezoneProvider = { "America/Toronto" },
                    remoteSyncEnabled = { false },
                )

            assertEquals(local, repository.load())
            repository.save(local.copy(messageNotifications = false))

            assertEquals(0, gateway.loadCount)
            assertEquals(null, gateway.lastUpdateRequest)
        }

    @Test
    fun serverBackedRepositoryPushesIndependentNotificationTogglesOnSave() =
        runBlocking {
            val localRepository = InMemoryAppSettingsRepository()
            val gateway = FakeNotificationPreferencesGateway()
            val repository = ServerBackedAppSettingsRepository(localRepository, gateway)

            repository.save(
                AppSettings(
                    notificationDetail = AppSettings.NOTIFICATION_STANDARD,
                    messageNotifications = false,
                    matchNotifications = true,
                    activityNotifications = true,
                    flirtNotifications = false,
                    albumRequestNotifications = true,
                    partnerInviteNotifications = false,
                    systemSafetyNotifications = false,
                    quietHoursEnabled = true,
                    quietHoursStartMinute = 23 * 60,
                    quietHoursEndMinute = 7 * 60,
                ),
            )

            val request = requireNotNull(gateway.lastUpdateRequest)
            assertEquals(AppSettings.NOTIFICATION_STANDARD, request.detailLevel)
            assertEquals(false, request.messagesEnabled)
            assertEquals(true, request.matchesEnabled)
            assertEquals(true, request.activityEnabled)
            // Each category is sent from its own client field, not reused from activity.
            assertEquals(false, request.flirtsEnabled)
            assertEquals(true, request.albumRequestsEnabled)
            assertEquals(false, request.partnerInvitesEnabled)
            // System/safety reflects the user setting, not a hardcoded true.
            assertEquals(false, request.systemSafetyEnabled)
            assertEquals(true, request.quietHoursEnabled)
            assertEquals(23 * 60, request.quietHoursStartMinute)
            assertEquals(7 * 60, request.quietHoursEndMinute)
            assertEquals("UTC", request.quietHoursTimezone)
        }

    @Test
    fun serverBackedRepositoryUsesDeviceTimezoneWhenSavingQuietHours() =
        runBlocking {
            val localRepository = InMemoryAppSettingsRepository()
            val gateway = FakeNotificationPreferencesGateway()
            val repository =
                ServerBackedAppSettingsRepository(
                    localRepository = localRepository,
                    notificationPreferencesGateway = gateway,
                    timezoneProvider = { "America/Toronto" },
                )

            repository.save(
                AppSettings(
                    quietHoursEnabled = true,
                    quietHoursStartMinute = 22 * 60,
                    quietHoursEndMinute = 8 * 60,
                ),
            )

            val request = requireNotNull(gateway.lastUpdateRequest)
            assertEquals(true, request.quietHoursEnabled)
            assertEquals(22 * 60, request.quietHoursStartMinute)
            assertEquals(8 * 60, request.quietHoursEndMinute)
            assertEquals("America/Toronto", request.quietHoursTimezone)
        }
}

private class InMemoryAppSettingsRepository(
    initialSettings: AppSettings = AppSettings(),
    private val beforeLoad: suspend () -> Unit = {},
) : AppSettingsRepository {
    var settings: AppSettings = initialSettings

    override suspend fun load(): AppSettings {
        beforeLoad()
        return settings
    }

    override suspend fun save(settings: AppSettings) {
        this.settings = settings
    }
}

private class FakeNotificationPreferencesGateway(
    var loadResult: AppResult<NotificationPreferencesDto> = AppResult.Success(notificationPreferences()),
    var updateResult: AppResult<NotificationPreferencesDto> = AppResult.Success(notificationPreferences()),
    private val beforeLoad: suspend () -> Unit = {},
) : NotificationPreferencesGateway {
    var lastUpdateRequest: UpdateNotificationPreferencesRequestDto? = null
    var loadCount: Int = 0

    override suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto> {
        beforeLoad()
        loadCount += 1
        return loadResult
    }

    override suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto,
    ): AppResult<NotificationPreferencesDto> {
        lastUpdateRequest = request
        return updateResult
    }
}

private fun notificationPreferences(
    detailLevel: String = AppSettings.NOTIFICATION_DISCREET,
    messagesEnabled: Boolean = true,
    matchesEnabled: Boolean = true,
    activityEnabled: Boolean = true,
    flirtsEnabled: Boolean = true,
    albumRequestsEnabled: Boolean = true,
    partnerInvitesEnabled: Boolean = true,
    systemSafetyEnabled: Boolean = true,
    quietHoursEnabled: Boolean = false,
    quietHoursStartMinute: Int = AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE,
    quietHoursEndMinute: Int = AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE,
    quietHoursTimezone: String = AppSettings.DEFAULT_QUIET_HOURS_TIMEZONE,
): NotificationPreferencesDto =
    NotificationPreferencesDto(
        detailLevel = detailLevel,
        messagesEnabled = messagesEnabled,
        matchesEnabled = matchesEnabled,
        activityEnabled = activityEnabled,
        flirtsEnabled = flirtsEnabled,
        albumRequestsEnabled = albumRequestsEnabled,
        partnerInvitesEnabled = partnerInvitesEnabled,
        systemSafetyEnabled = systemSafetyEnabled,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinute = quietHoursStartMinute,
        quietHoursEndMinute = quietHoursEndMinute,
        quietHoursTimezone = quietHoursTimezone,
    )
