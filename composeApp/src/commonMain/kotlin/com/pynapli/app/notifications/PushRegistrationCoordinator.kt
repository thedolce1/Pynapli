package com.pynapli.app.notifications

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.settings.AppSettings

class PushRegistrationCoordinator(
    private val tokenProvider: PushTokenProvider,
    private val repository: PushDeviceRegistrationRepository,
    private val registrationStore: PushRegistrationStore,
    private val platform: String,
    private val appVersion: String,
    private val localeProvider: () -> String?,
    private val timezoneProvider: () -> String?,
    private val notificationsAllowed: () -> Boolean = { true },
) {
    suspend fun syncAuthenticatedDevice(
        userId: String?,
        settings: AppSettings,
    ) {
        val currentUserId = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val token = tokenProvider.currentToken()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val registration =
            PushDeviceRegistration(
                platform = platform,
                deviceToken = token,
                appVersion = appVersion,
                locale = localeProvider()?.trim()?.takeIf { it.isNotEmpty() },
                timezone = timezoneProvider()?.trim()?.takeIf { it.isNotEmpty() },
                notificationsEnabled = settings.pushNotificationsEnabled && notificationsAllowed(),
            )
        val fingerprint = registration.fingerprint(currentUserId)
        if (registrationStore.currentFingerprint() == fingerprint) return

        when (repository.register(registration)) {
            is AppResult.Success ->
                registrationStore.save(
                    PushRegistrationSnapshot(
                        fingerprint = fingerprint,
                        platform = platform,
                        deviceToken = token,
                    ),
                )
            is AppResult.Failure -> Unit
        }
    }

    suspend fun unregisterCurrentDevice() {
        val snapshot = registrationStore.currentSnapshot()
        val token =
            tokenProvider.currentToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: snapshot?.deviceToken
                ?: return
        val unregisterPlatform = snapshot?.platform ?: platform

        when (repository.unregister(unregisterPlatform, token)) {
            is AppResult.Success -> registrationStore.clear()
            is AppResult.Failure -> Unit
        }
    }

    private val AppSettings.pushNotificationsEnabled: Boolean
        get() = messageNotifications || matchNotifications || activityNotifications
}

interface PushTokenProvider {
    suspend fun currentToken(): String?
}

interface PushRegistrationStore {
    fun currentSnapshot(): PushRegistrationSnapshot?

    fun currentFingerprint(): String? = currentSnapshot()?.fingerprint

    fun save(snapshot: PushRegistrationSnapshot)

    fun clear()
}

data class PushRegistrationSnapshot(
    val fingerprint: String,
    val platform: String,
    val deviceToken: String,
)

data class PushDeviceRegistration(
    val platform: String,
    val deviceToken: String,
    val appVersion: String?,
    val locale: String?,
    val timezone: String?,
    val notificationsEnabled: Boolean,
)

interface PushDeviceRegistrationRepository {
    suspend fun register(registration: PushDeviceRegistration): AppResult<Unit>

    suspend fun unregister(
        platform: String,
        deviceToken: String,
    ): AppResult<Unit>
}

class ApiPushDeviceRegistrationRepository(
    private val apiClient: PynapliApiClient,
) : PushDeviceRegistrationRepository {
    override suspend fun register(registration: PushDeviceRegistration): AppResult<Unit> =
        when (
            val result =
                apiClient.registerPushDevice(
                    RegisterPushDeviceRequestDto(
                        platform = registration.platform,
                        deviceToken = registration.deviceToken,
                        appVersion = registration.appVersion,
                        locale = registration.locale,
                        timezone = registration.timezone,
                        notificationsEnabled = registration.notificationsEnabled,
                    ),
                )
        ) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }

    override suspend fun unregister(
        platform: String,
        deviceToken: String,
    ): AppResult<Unit> =
        apiClient.unregisterPushDevice(
            UnregisterPushDeviceRequestDto(
                platform = platform,
                deviceToken = deviceToken,
            ),
        )
}

private fun PushDeviceRegistration.fingerprint(userId: String): String =
    listOf(
        userId,
        platform,
        deviceToken,
        appVersion.orEmpty(),
        locale.orEmpty(),
        timezone.orEmpty(),
        notificationsEnabled.toString(),
    ).joinToString(separator = "\u001f")
