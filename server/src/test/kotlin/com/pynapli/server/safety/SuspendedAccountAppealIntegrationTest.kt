package com.pynapli.server.safety

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SuspendedAccountAppealIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val appeals = AppealService(JdbcAppealRepository(dataSource))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun suspend(
        userId: String,
        status: String = "suspended",
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE users SET status = ?, updated_at = now() WHERE id = ?").use {
                it.setString(1, status)
                it.setObject(2, UUID.fromString(userId))
                it.executeUpdate()
            }
        }
    }

    private suspend fun account(email: String = "suspended@example.com") = auth.signupEmail(email, PASSWORD, "1990-01-01", true, true, true, null, "android", "1.0").user.userId

    private fun body(
        email: String,
        password: String,
    ) = """{"email":"$email","password":"$password","reason":"I think this was a mistake."}"""

    @Test
    fun aWrongPasswordNeverRevealsThatAnAccountIsSuspended() =
        runBlocking<Unit> {
            suspend(account())

            val wrong = assertFailsWith<AuthException> { auth.loginEmail("suspended@example.com", "wrong-password-1", null, "android", "1.0") }
            assertEquals("INVALID_CREDENTIALS", wrong.code)
            val right = assertFailsWith<AuthException> { auth.loginEmail("suspended@example.com", PASSWORD, null, "android", "1.0") }
            assertEquals("ACCOUNT_NOT_ACTIVE", right.code)
        }

    @Test
    fun aSuspendedAccountCanAppealWithItsPasswordOnce() =
        testApplication {
            val userId = runBlocking { account() }
            suspend(userId)
            application {
                configureSerialization()
                configureStatusPages()
                configureAppealRoutes(auth, appeals)
            }

            val first =
                client.post("/v1/auth/account-appeal") {
                    contentType(ContentType.Application.Json)
                    setBody(body("suspended@example.com", PASSWORD))
                }
            assertEquals(HttpStatusCode.OK, first.status)
            assertTrue(first.bodyAsText().contains("account_suspension"))
            val stored = runBlocking { appeals.listAppeals(UUID.fromString(userId), 10) }
            assertEquals(listOf("account_suspension"), stored.appeals.map { it.actionType })

            val again =
                client.post("/v1/auth/account-appeal") {
                    contentType(ContentType.Application.Json)
                    setBody(body("suspended@example.com", PASSWORD))
                }
            assertTrue(again.bodyAsText().contains("APPEAL_ALREADY_OPEN"))
        }

    @Test
    fun aBannedAccountAppealsTheBan() =
        testApplication {
            val userId = runBlocking { account() }
            suspend(userId, status = "banned")
            application {
                configureSerialization()
                configureStatusPages()
                configureAppealRoutes(auth, appeals)
            }

            val response =
                client.post("/v1/auth/account-appeal") {
                    contentType(ContentType.Application.Json)
                    setBody(body("suspended@example.com", PASSWORD))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("account_ban"))
        }

    @Test
    fun theWrongPasswordOrAnActiveAccountCannotAppeal() =
        testApplication {
            val suspendedId = runBlocking { account() }
            suspend(suspendedId)
            runBlocking { account("active@example.com") }
            application {
                configureSerialization()
                configureStatusPages()
                configureAppealRoutes(auth, appeals)
            }

            val wrong =
                client.post("/v1/auth/account-appeal") {
                    contentType(ContentType.Application.Json)
                    setBody(body("suspended@example.com", "wrong-password-1"))
                }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertTrue(wrong.bodyAsText().contains("INVALID_CREDENTIALS"))

            val active =
                client.post("/v1/auth/account-appeal") {
                    contentType(ContentType.Application.Json)
                    setBody(body("active@example.com", PASSWORD))
                }
            assertEquals(HttpStatusCode.Conflict, active.status)
            assertTrue(runBlocking { appeals.listAppeals(UUID.fromString(suspendedId), 10) }.appeals.isEmpty())
        }

    private companion object {
        const val PASSWORD = "appeal-password-1"
    }
}
