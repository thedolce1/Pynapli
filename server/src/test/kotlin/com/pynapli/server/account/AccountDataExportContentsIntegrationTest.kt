package com.pynapli.server.account

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The export runs every section against the real schema and leaves secrets out. */
class AccountDataExportContentsIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun sql(
        statement: String,
        vararg params: Any,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(statement).use {
                params.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                it.executeUpdate()
            }
        }
    }

    @Test
    fun theExportCoversEverySectionWithoutSecrets() =
        runBlocking<Unit> {
            val me = auth.signupEmail("export@example.com", "export-password-1", "1990-01-01", true, true, true, null, "android", "1.0")
            val other = auth.signupEmail("other@example.com", "export-password-1", "1990-01-01", true, true, true, null, "android", "1.0")
            val myId = UUID.fromString(me.user.userId)
            val otherId = UUID.fromString(other.user.userId)
            sql("INSERT INTO swipe_actions (id, actor_user_id, target_user_id, action) VALUES (?, ?, ?, 'like')", UUID.randomUUID(), myId, otherId)
            // Postgres orders uuids by their bytes (= the hex text), not by java.util.UUID.compareTo
            // (signed longs), so pick the canonical pair the way the matches check constraint does.
            val (first, second) = listOf(myId, otherId).sortedBy { it.toString() }
            sql("INSERT INTO matches (id, user_a, user_b) VALUES (?, ?, ?)", UUID.randomUUID(), first, second)
            sql("INSERT INTO user_blocks (id, blocker_user_id, blocked_user_id, reason) VALUES (?, ?, ?, 'qa')", UUID.randomUUID(), myId, otherId)
            sql(
                "INSERT INTO push_devices (id, user_id, platform, device_token) VALUES (?, ?, 'android', 'secret-push-token-123')",
                UUID.randomUUID(),
                myId,
            )

            val archive = JdbcAccountDataExportArchiver(dataSource).buildArchive(myId)
            val text = archive.bytes.decodeToString()
            val document = Json.parseToJsonElement(text).jsonObject

            JdbcAccountDataExportArchiver.EXPORT_SECTIONS.forEach { (name, _) ->
                assertTrue(document[name] is JsonArray, "section $name present")
            }
            assertEquals(1, (document["likesAndPasses"] as JsonArray).size)
            assertTrue(text.contains(otherId.toString()), "the match names the other person")
            assertEquals(1, (document["blocks"] as JsonArray).size)
            assertEquals(1, (document["signIns"] as JsonArray).size)
            assertEquals(1, (document["pushDevices"] as JsonArray).size)

            listOf("secret-push-token-123", "access_token_hash", "refresh_token_hash", "password_hash", "device_token", "storage_object_key")
                .forEach { secret -> assertFalse(text.contains(secret), "export must not contain $secret") }
        }
}
