package com.pynapli.server.retention

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.signUpVerified
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataRetentionIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val retention = DataRetentionService(dataSource, expiredMessageGrace = Duration.ofDays(30), batchSize = 2)

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun user(email: String) = UUID.fromString(auth.signUpVerified(email, "retention-pass-1", platform = "android", appVersion = "1.0").user.userId)

    private fun execute(
        sql: String,
        vararg args: Any?,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private fun exists(
        sql: String,
        vararg args: Any?,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { it.next() }
            }
        }

    private fun chat(
        a: UUID,
        b: UUID,
        timerSeconds: Int?,
    ): UUID {
        val id = UUID.randomUUID()
        execute("INSERT INTO conversations (id, kind, origin, created_by_user_id) VALUES (?, 'direct', 'grid', ?)", id, a)
        listOf(a, b).forEach { member ->
            execute("INSERT INTO conversation_participants (conversation_id, user_id, message_expiry_seconds) VALUES (?, ?, ?)", id, member, timerSeconds)
        }
        return id
    }

    private fun message(
        chat: UUID,
        sender: UUID,
        ageDays: Long,
        type: String = "user",
        photo: UUID? = null,
    ): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO messages (id, conversation_id, sender_user_id, body, message_type, media_asset_id, created_at) " +
                "VALUES (?, ?, ?, 'hello', ?, ?, now() - make_interval(days => ?))",
            id,
            chat,
            sender,
            type,
            photo,
            ageDays.toInt(),
        )
        return id
    }

    private fun photo(owner: UUID): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO media_assets (id, owner_user_id, media_type, storage_provider, storage_bucket, storage_object_key, content_type, byte_size) " +
                "VALUES (?, ?, 'image', 'local', 'test', ?, 'image/jpeg', 10)",
            id,
            owner,
            "key-$id",
        )
        return id
    }

    private fun messageExists(id: UUID) = exists("SELECT 1 FROM messages WHERE id = ?", id)

    @Test
    fun expiredDisappearingMessagesAreDeletedAfterTheGraceUnlessHeldForModeration() =
        runBlocking<Unit> {
            val me = user("me@example.com")
            val them = user("them@example.com")
            // 24h timer: a message older than 1 day + 30 days grace goes.
            val timed = chat(me, them, timerSeconds = 86_400)
            val untimed = chat(me, them, timerSeconds = null)
            val chatPhoto = photo(them)
            val gone = listOf(message(timed, them, ageDays = 40), message(timed, me, ageDays = 35), message(timed, them, ageDays = 32, photo = chatPhoto))
            val stillInGrace = message(timed, them, ageDays = 10)
            val systemNotice = message(timed, them, ageDays = 40, type = "system")
            val noTimer = message(untimed, them, ageDays = 400)
            val reported = message(timed, them, ageDays = 40)
            execute(
                "INSERT INTO safety_reports (id, reporter_user_id, reported_user_id, target_type, target_id, category, status) VALUES (?, ?, ?, 'message', ?, 'harassment_or_threats', 'open')",
                UUID.randomUUID(),
                me,
                them,
                reported.toString(),
            )

            val summary = retention.runOnce()

            assertEquals(3, summary.expiredMessages, "batches of 2 must keep going until the backlog is done")
            gone.forEach { assertFalse(messageExists(it)) }
            listOf(stillInGrace, systemNotice, noTimer, reported).forEach { assertTrue(messageExists(it)) }
            assertTrue(exists("SELECT 1 FROM media_assets WHERE id = ? AND deleted_at IS NOT NULL", chatPhoto), "the chat photo goes with it")
        }

    @Test
    fun deadSessionsAndUsedResetCodesAreRemovedButLiveOnesStay() =
        runBlocking<Unit> {
            val me = user("me@example.com")
            val liveSession = UUID.randomUUID()
            val deadSession = UUID.randomUUID()
            execute(
                "INSERT INTO user_sessions (id, user_id, access_token_hash, refresh_token_hash, access_expires_at, refresh_expires_at, revoked_at) " +
                    "VALUES (?, ?, 'a-live', 'r-live', now() + interval '1 hour', now() + interval '30 days', NULL), " +
                    "(?, ?, 'a-dead', 'r-dead', now() - interval '60 days', now() - interval '40 days', now() - interval '45 days')",
                liveSession,
                me,
                deadSession,
                me,
            )
            execute(
                "INSERT INTO password_reset_codes (id, user_id, code_hash, expires_at, consumed_at) VALUES (?, ?, 'h1', now() - interval '3 days', NULL), (?, ?, 'h2', now() + interval '10 minutes', NULL)",
                UUID.randomUUID(),
                me,
                UUID.randomUUID(),
                me,
            )

            val summary = retention.runOnce()

            assertEquals(1, summary.deadSessions)
            assertNotNull(liveSession.takeIf { exists("SELECT 1 FROM user_sessions WHERE id = ?", it) })
            assertNull(deadSession.takeIf { exists("SELECT 1 FROM user_sessions WHERE id = ?", it) })
            assertEquals(1, summary.passwordResetCodes)
            assertTrue(exists("SELECT 1 FROM password_reset_codes WHERE code_hash = 'h2'"))
        }
}
