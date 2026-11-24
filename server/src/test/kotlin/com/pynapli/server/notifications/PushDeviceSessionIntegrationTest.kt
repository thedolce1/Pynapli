package com.pynapli.server.notifications

import com.pynapli.server.account.JdbcAccountLifecycleRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.signUpVerified
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A phone stops getting notifications when the session that registered it ends. */
class PushDeviceSessionIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val notifications = JdbcNotificationRepository(dataSource)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun signUp() = auth.signUpVerified("push@example.com", "push-password-1", platform = "android", appVersion = "1.0")

    private suspend fun register(
        userId: UUID,
        token: String,
        sessionId: UUID?,
    ) = notifications.upsertPushDevice(
        userId = userId,
        registration = PushDeviceRegistration("android", token, "1.0", null, null, true, sessionId = sessionId),
        deviceIdFactory = UUID::randomUUID,
    )

    private suspend fun tokens(userId: UUID) = notifications.listActivePushTargets(userId).map { it.deviceToken }.toSet()

    @Test
    fun aDeviceStopsReceivingWhenItsSessionIsSignedOut() =
        runBlocking<Unit> {
            val phone = signUp()
            val userId = UUID.fromString(phone.user.userId)
            val tablet = auth.loginEmail("push@example.com", "push-password-1", null, "android", "1.0")
            register(userId, "phone-token-0000000000", auth.sessionIdFor(phone.accessToken))
            register(userId, "tablet-token-000000000", auth.sessionIdFor(tablet.accessToken))
            assertEquals(setOf("phone-token-0000000000", "tablet-token-000000000"), tokens(userId))
            assertEquals(2, notifications.getDeliveryGate(userId)?.activeDeviceCount)

            auth.logout(tablet.accessToken)

            assertEquals(setOf("phone-token-0000000000"), tokens(userId))
            assertEquals(1, notifications.getDeliveryGate(userId)?.activeDeviceCount)
        }

    @Test
    fun signingInAgainOnTheSamePhoneMovesTheDeviceToTheNewSession() =
        runBlocking<Unit> {
            val first = signUp()
            val userId = UUID.fromString(first.user.userId)
            register(userId, "phone-token-0000000000", auth.sessionIdFor(first.accessToken))
            auth.logout(first.accessToken)
            assertTrue(tokens(userId).isEmpty())

            val again = auth.loginEmail("push@example.com", "push-password-1", null, "android", "1.0")
            register(userId, "phone-token-0000000000", auth.sessionIdFor(again.accessToken))

            assertEquals(setOf("phone-token-0000000000"), tokens(userId))
        }

    @Test
    fun devicesRegisteredBeforeSessionsWereRecordedKeepWorkingUntilTheAccountIsDeleted() =
        runBlocking<Unit> {
            val phone = signUp()
            val userId = UUID.fromString(phone.user.userId)
            register(userId, "legacy-token-000000000", sessionId = null)
            assertEquals(setOf("legacy-token-000000000"), tokens(userId))

            JdbcAccountLifecycleRepository(dataSource, com.pynapli.server.contactblocks.ContactBlockTokens.localDevelopment()).deleteAccount(userId)

            assertTrue(tokens(userId).isEmpty())
        }
}
