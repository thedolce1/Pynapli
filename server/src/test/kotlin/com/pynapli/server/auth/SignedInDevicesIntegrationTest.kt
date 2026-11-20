package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.signUpVerified
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignedInDevicesIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val authService = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val devices =
        SignedInDevicesService(
            repository = JdbcSignedInDevicesRepository(dataSource),
            onSessionsRevoked = authService::invalidateCachedSessionsForUser,
        )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun signUp(email: String) = authService.signUpVerified(email, PASSWORD, deviceId = "phone", platform = "android", appVersion = "1.0")

    private suspend fun signIn(
        email: String,
        platform: String,
    ) = authService.loginEmail(email, PASSWORD, deviceId = platform, platform = platform, appVersion = "1.0")

    @Test
    fun listsTheAccountsLiveSessionsWithThisDeviceFirst() =
        runBlocking<Unit> {
            val phone = signUp("devices@example.com")
            signIn("devices@example.com", "web")
            val other = signUp("someone-else@example.com")
            val userId = UUID.fromString(phone.user.userId)

            val list = devices.list(userId, phone.accessToken).devices

            assertEquals(2, list.size, "only this account's sessions")
            assertTrue(list.first().current)
            assertEquals("android", list.first().platform)
            assertEquals(listOf(false), list.drop(1).map { it.current })
            assertTrue(list.none { it.sessionId == other.user.userId })
        }

    @Test
    fun signingOutAnotherDeviceEndsItsSessionOnly() =
        runBlocking<Unit> {
            val phone = signUp("devices@example.com")
            val web = signIn("devices@example.com", "web")
            val userId = UUID.fromString(phone.user.userId)
            val webSession = devices.list(userId, phone.accessToken).devices.single { !it.current }

            devices.signOut(userId, phone.accessToken, webSession.sessionId)

            assertFailsWith<AuthException> { authService.authenticate(web.accessToken) }
            assertFailsWith<AuthException> { authService.refresh(web.refreshToken) }
            assertEquals(userId, authService.authenticate(phone.accessToken).id)
            assertEquals(1, devices.list(userId, phone.accessToken).devices.size)
        }

    @Test
    fun thisDeviceAndOtherPeoplesSessionsCannotBeSignedOutFromHere() =
        runBlocking<Unit> {
            val phone = signUp("devices@example.com")
            val stranger = signUp("someone-else@example.com")
            val userId = UUID.fromString(phone.user.userId)
            val mine = devices.list(userId, phone.accessToken).devices.single()
            val theirs = devices.list(UUID.fromString(stranger.user.userId), stranger.accessToken).devices.single()

            assertEquals("CANNOT_SIGN_OUT_CURRENT_SESSION", assertFailsWith<AuthException> { devices.signOut(userId, phone.accessToken, mine.sessionId) }.code)
            assertEquals("SESSION_NOT_FOUND", assertFailsWith<AuthException> { devices.signOut(userId, phone.accessToken, theirs.sessionId) }.code)
            assertEquals("SESSION_NOT_FOUND", assertFailsWith<AuthException> { devices.signOut(userId, phone.accessToken, "not-a-uuid") }.code)
            authService.authenticate(stranger.accessToken)
        }

    @Test
    fun signOutEverywhereElseKeepsOnlyThisDevice() =
        runBlocking<Unit> {
            val phone = signUp("devices@example.com")
            val web = signIn("devices@example.com", "web")
            val desktop = signIn("devices@example.com", "desktop")
            val userId = UUID.fromString(phone.user.userId)

            assertEquals(2, devices.signOutOthers(userId, phone.accessToken))

            assertFailsWith<AuthException> { authService.authenticate(web.accessToken) }
            assertFailsWith<AuthException> { authService.authenticate(desktop.accessToken) }
            assertEquals(userId, authService.authenticate(phone.accessToken).id)
            assertEquals(0, devices.signOutOthers(userId, phone.accessToken))
        }

    private companion object {
        const val PASSWORD = "devices-password-1"
    }
}
