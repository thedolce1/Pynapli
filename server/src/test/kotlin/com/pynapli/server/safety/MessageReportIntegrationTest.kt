package com.pynapli.server.safety

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportTargetType
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageReportIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val reports =
        ReportService(
            repository = JdbcReportRepository(dataSource),
            evidenceLookup = JdbcReportEvidenceLookup(dataSource),
        )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun user(email: String) = UUID.fromString(auth.signupEmail(email, "report-password-1", "1990-01-01", true, true, true, null, "android", "1.0").user.userId)

    private fun conversation(vararg members: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO conversations (id, kind, origin, created_by_user_id) VALUES (?, 'direct', 'grid', ?)").use {
                it.setObject(1, id)
                it.setObject(2, members.first())
                it.executeUpdate()
            }
            members.forEach { member ->
                connection.prepareStatement("INSERT INTO conversation_participants (conversation_id, user_id) VALUES (?, ?)").use {
                    it.setObject(1, id)
                    it.setObject(2, member)
                    it.executeUpdate()
                }
            }
        }
        return id
    }

    private fun message(
        conversationId: UUID,
        sender: UUID,
        body: String,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO messages (id, conversation_id, sender_user_id, body) VALUES (?, ?, ?, ?)").use {
                it.setObject(1, id)
                it.setObject(2, conversationId)
                it.setObject(3, sender)
                it.setString(4, body)
                it.executeUpdate()
            }
        }
        return id
    }

    private fun evidenceOf(reportId: String): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT evidence_text FROM safety_reports WHERE id = ?").use {
                it.setObject(1, UUID.fromString(reportId))
                it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private suspend fun report(
        reporter: UUID,
        reported: UUID,
        messageId: Any?,
    ) = reports.submitReport(
        reporterUserId = reporter,
        request =
            CreateReportRequestDto(
                reportedUserId = reported.toString(),
                targetType = ReportTargetType.MESSAGE,
                targetId = messageId?.toString(),
                category = ReportCategory.HARASSMENT_OR_THREATS,
            ),
    )

    @Test
    fun reportingAMessageKeepsWhatWasSaidForModerators() =
        runBlocking<Unit> {
            val me = user("me@example.com")
            val them = user("them@example.com")
            val chat = conversation(me, them)
            val abusive = message(chat, them, "abusive text")

            val created = report(me, them, abusive)

            assertEquals("abusive text", evidenceOf(created.reportId))
        }

    @Test
    fun onlyMessagesTheyWroteInAChatYouAreInCanBeReported() =
        runBlocking<Unit> {
            val me = user("me@example.com")
            val them = user("them@example.com")
            val stranger = user("stranger@example.com")
            val ourChat = conversation(me, them)
            val theirOtherChat = conversation(them, stranger)
            val mine = message(ourChat, me, "my own words")
            val elsewhere = message(theirOtherChat, them, "not said to me")

            listOf(mine, elsewhere, UUID.randomUUID(), "not-a-uuid", null).forEach { target ->
                val error = assertFailsWith<SafetyException> { report(me, them, target) }
                assertEquals("REPORT_TARGET_NOT_FOUND", error.code, "target $target")
            }
        }
}
