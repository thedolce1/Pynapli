package com.pynapli.app.notifications

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PushRegistrationCoordinatorTest {
    @Test
    fun syncRegistersAuthenticatedDevice() =
        runBlocking {
            val repository = FakePushDeviceRegistrationRepository()
            val store = InMemoryPushRegistrationStore()
            val coordinator = coordinator(repository = repository, store = store)

            coordinator.syncAuthenticatedDevice(
                userId = "user-1",
                settings = AppSettings(),
            )

            assertEquals(1, repository.registered.size)
            assertEquals("android", repository.registered.single().platform)
            assertEquals("local-dev-token-1234567890", repository.registered.single().deviceToken)
            assertEquals(true, repository.registered.single().notificationsEnabled)
            assertEquals(store.currentSnapshot()?.deviceToken, repository.registered.single().deviceToken)
        }

    @Test
    fun syncSkipsWhenRegistrationFingerprintIsUnchanged() =
        runBlocking {
            val repository = FakePushDeviceRegistrationRepository()
            val store = InMemoryPushRegistrationStore()
            val coordinator = coordinator(repository = repository, store = store)
            val settings = AppSettings()

            coordinator.syncAuthenticatedDevice("user-1", settings)
            coordinator.syncAuthenticatedDevice("user-1", settings)

            assertEquals(1, repository.registered.size)
        }

    @Test
    fun syncReRegistersWhenTheDeviceTokenRotates() =
        runBlocking {
            // FCM rotates tokens (reinstall/restore, or a long-idle install). onNewToken clears the
            // store; the next sync must re-register the new token. This is the guarantee the
            // foreground re-sync (MainActivity.onStart) relies on to close the rotation gap.
            val repository = FakePushDeviceRegistrationRepository()
            val store = InMemoryPushRegistrationStore()
            val tokenProvider = MutablePushTokenProvider("token-old")
            val coordinator = coordinator(tokenProvider = tokenProvider, repository = repository, store = store)

            coordinator.syncAuthenticatedDevice("user-1", AppSettings())
            tokenProvider.token = "token-new"
            coordinator.syncAuthenticatedDevice("user-1", AppSettings())

            assertEquals(2, repository.registered.size)
            assertEquals("token-new", repository.registered.last().deviceToken)
            assertEquals("token-new", store.currentSnapshot()?.deviceToken)
        }

    @Test
    fun syncUpdatesWhenNotificationEnabledStateChanges() =
        runBlocking {
            val repository = FakePushDeviceRegistrationRepository()
            val store = InMemoryPushRegistrationStore()
            val coordinator = coordinator(repository = repository, store = store)

            coordinator.syncAuthenticatedDevice("user-1", AppSettings())
            coordinator.syncAuthenticatedDevice(
                "user-1",
                AppSettings(
                    messageNotifications = false,
                    matchNotifications = false,
                    activityNotifications = false,
                ),
            )

            assertEquals(2, repository.registered.size)
            assertEquals(false, repository.registered.last().notificationsEnabled)
        }

    @Test
    fun syncRegistersDisabledWhenSystemNotificationsAreNotAllowed() =
        runBlocking {
            val repository = FakePushDeviceRegistrationRepository()
            val store = InMemoryPushRegistrationStore()
            val coordinator =
                PushRegistrationCoordinator(
                    tokenProvider = StaticPushTokenProvider("local-dev-token-1234567890"),
                    repository = repository,
                    registrationStore = store,
                    platform = "android",
                    appVersion = "0.1.0",
                    localeProvider = { "en-CA" },
                    timezoneProvider = { "America/Toronto" },
                    notificationsAllowed = { false },
                )

            coordinator.syncAuthenticatedDevice("user-1", AppSettings())

            assertEquals(false, repository.registered.single().notificationsEnabled)
        }

    @Test
    fun syncDoesNotStoreFingerprintWhenRegistrationFails() =
        runBlocking {
            val repository =
                FakePushDeviceRegistrationRepository(
                    registerResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline")),
                )
            val store = InMemoryPushRegistrationStore()
            val coordinator = coordinator(repository = repository, store = store)

            coordinator.syncAuthenticatedDevice("user-1", AppSettings())

            assertEquals(null, store.currentSnapshot())
        }

    @Test
    fun unregisterUsesStoredSnapshotAndClearsOnSuccess() =
        runBlocking {
            val repository = FakePushDeviceRegistrationRepository()
            val store = InMemoryPushRegistrationStore()
            val coordinator = coordinator(repository = repository, store = store)

            coordinator.syncAuthenticatedDevice("user-1", AppSettings())
            coordinator.unregisterCurrentDevice()

            assertEquals("android" to "local-dev-token-1234567890", repository.unregistered.single())
            assertEquals(null, store.currentSnapshot())
        }

    private fun coordinator(
        tokenProvider: PushTokenProvider = StaticPushTokenProvider("local-dev-token-1234567890"),
        repository: FakePushDeviceRegistrationRepository,
        store: InMemoryPushRegistrationStore,
    ): PushRegistrationCoordinator =
        PushRegistrationCoordinator(
            tokenProvider = tokenProvider,
            repository = repository,
            registrationStore = store,
            platform = "android",
            appVersion = "0.1.0",
            localeProvider = { "en-CA" },
            timezoneProvider = { "America/Toronto" },
        )
}

private class StaticPushTokenProvider(
    private val token: String?,
) : PushTokenProvider {
    override suspend fun currentToken(): String? = token
}

private class MutablePushTokenProvider(
    var token: String?,
) : PushTokenProvider {
    override suspend fun currentToken(): String? = token
}

private class InMemoryPushRegistrationStore : PushRegistrationStore {
    private var snapshot: PushRegistrationSnapshot? = null

    override fun currentSnapshot(): PushRegistrationSnapshot? = snapshot

    override fun save(snapshot: PushRegistrationSnapshot) {
        this.snapshot = snapshot
    }

    override fun clear() {
        snapshot = null
    }
}

private class FakePushDeviceRegistrationRepository(
    private val registerResult: AppResult<Unit> = AppResult.Success(Unit),
    private val unregisterResult: AppResult<Unit> = AppResult.Success(Unit),
) : PushDeviceRegistrationRepository {
    val registered = mutableListOf<PushDeviceRegistration>()
    val unregistered = mutableListOf<Pair<String, String>>()

    override suspend fun register(registration: PushDeviceRegistration): AppResult<Unit> {
        registered += registration
        return registerResult
    }

    override suspend fun unregister(
        platform: String,
        deviceToken: String,
    ): AppResult<Unit> {
        unregistered += platform to deviceToken
        return unregisterResult
    }
}
