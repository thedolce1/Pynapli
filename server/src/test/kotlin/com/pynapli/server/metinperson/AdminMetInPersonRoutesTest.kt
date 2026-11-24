package com.pynapli.server.metinperson

import com.pynapli.server.admin.ADMIN_ROLE_OPERATOR
import com.pynapli.server.admin.ADMIN_ROLE_SENIOR
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.profile.JdbcProfileRepository
import com.pynapli.server.profile.toDto
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.testServerConfig
import com.pynapli.shared.network.dto.ConfirmMetInPersonRequestDto
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The admin view and actions take the senior + MFA + reason gate and write their own audit rows. */
class AdminMetInPersonRoutesTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val clock = MetInPersonTestClock(Instant.parse("2026-11-18T12:00:00Z"))
    private val tokens = MetInPersonTokens("met-in-person-admin-test-key-0123456789")
    private val fx = MetInPersonFixtures(dataSource, clock)
    private val service =
        MetInPersonService(
            dataSource,
            tokens,
            com.pynapli.server.contactblocks
                .JdbcContactBlockVisibility(
                    dataSource,
                    com.pynapli.server.contactblocks.ContactBlockTokens
                        .localDevelopment(),
                ),
            clock,
        )
    private val admin = AdminMetInPersonService(dataSource, tokens, clock)
    private val batch = MetInPersonBadgeBatch(dataSource, tokens, clock, Random(1))

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun config(
        roles: Map<String, String> = emptyMap(),
        mfaEnforced: Boolean = false,
    ): ServerConfig = testServerConfig().copy(adminApiToken = TOKEN, adminRoles = roles, adminMfaEnforced = mfaEnforced)

    private fun Application.install(config: ServerConfig) {
        configureSerialization()
        configureStatusPages()
        configureAdminMetInPersonRoutes(config, admin)
    }

    private fun HttpRequestBuilder.adminHeaders(
        actorId: String,
        reason: String? = "met in person review",
    ) {
        header("X-Admin-Token", TOKEN)
        header("X-Admin-Id", actorId)
        if (reason != null) header("X-Admin-Reason", reason)
    }

    private fun HttpRequestBuilder.jsonBody(json: String) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private fun auditRows(action: String): Int = fx.count("SELECT count(*) FROM admin_audit_log WHERE action = ?", action)

    private fun settle() =
        runBlocking {
            repeat(8) { batch.recomputeNow() }
        }

    /** Sam confirmed by three members; returns (subject, vouchers). */
    private fun confirmedSubject(): Pair<UUID, List<UUID>> =
        runBlocking {
            val subject = fx.member("Sam")
            fx.optIn(subject)
            val vouchers =
                (1..3).map { index ->
                    val voucher = fx.member("Voucher $index")
                    service.confirm(voucher, fx.talked(voucher, subject).toString(), ConfirmMetInPersonRequestDto(subject.toString()))
                    voucher
                }
            subject to vouchers
        }

    private fun personBadge(subject: UUID): Boolean =
        runBlocking {
            val stranger = fx.member("Stranger ${UUID.randomUUID()}")
            assertNotNull(JdbcProfileRepository(dataSource).getPublicProfile(stranger, subject)).toDto(null).metInPerson
        }

    private fun firstVouchId(subject: UUID): UUID = UUID.fromString(fx.queryText("SELECT id::text FROM vouches WHERE subject_token = ? ORDER BY id LIMIT 1", tokens.subject(subject)))

    @Test
    fun theViewNeedsTheAdminToken() =
        testApplication {
            application { install(config()) }
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/v1/admin/met-in-person/users/${UUID.randomUUID()}") { header("X-Admin-Id", "ops-1") }.status,
            )
        }

    @Test
    fun anOperatorCannotSeeVouchers() =
        testApplication {
            val (subject, _) = confirmedSubject()
            application { install(config(roles = mapOf("ops-1" to ADMIN_ROLE_OPERATOR))) }
            val response = client.get("/v1/admin/met-in-person/users/$subject") { adminHeaders("ops-1") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("ADMIN_ROLE_REQUIRED"))
            assertEquals(0, auditRows("met_in_person.view"))
        }

    @Test
    fun theViewNeedsStepUpMfaWhenEnforced() =
        testApplication {
            val (subject, _) = confirmedSubject()
            application { install(config(mfaEnforced = true)) }
            val response = client.get("/v1/admin/met-in-person/users/$subject") { adminHeaders("senior-1") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("ADMIN_MFA_REQUIRED"))
        }

    @Test
    fun theViewNeedsAReason() =
        testApplication {
            val (subject, _) = confirmedSubject()
            application { install(config()) }
            val response = client.get("/v1/admin/met-in-person/users/$subject") { adminHeaders("senior-1", reason = null) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, auditRows("met_in_person.view"))
        }

    @Test
    fun aSeniorAdminSeesResolvedVouchersFlagsAndTheViewIsAudited() =
        testApplication {
            val (subject, vouchers) = confirmedSubject()
            // Sam confirmed voucher 1 back; voucher 2 used Sam's device before confirming.
            runBlocking {
                service.confirm(
                    subject,
                    dataSource.directConversation(subject, vouchers[0]).toString(),
                    ConfirmMetInPersonRequestDto(vouchers[0].toString()),
                )
            }
            fx.shareDevice(vouchers[1], subject)
            application { install(config(roles = mapOf("senior-1" to ADMIN_ROLE_SENIOR))) }

            val response = client.get("/v1/admin/met-in-person/users/$subject") { adminHeaders("senior-1") }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            vouchers.forEach { assertTrue(body.contains(it.toString()), "voucher resolved through K3") }
            assertTrue(body.contains("\"qualifyingPersonCount\":2"), body)
            assertTrue(body.contains("\"reciprocal\":true"))
            assertTrue(body.contains("\"sharedDevice\":true"))
            assertEquals(1, auditRows("met_in_person.view"))
            val auditText = allAuditText()
            vouchers.forEach { assertFalse(auditText.contains(it.toString()), "the audit log never names a voucher in clear") }
        }

    private fun allAuditText(): String =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT coalesce(target_id::text, '') || ' ' || metadata::text FROM admin_audit_log").use { rs ->
                    buildString { while (rs.next()) append(rs.getString(1)) }
                }
            }
        }

    @Test
    fun voidingAndUnvoidingAreAuditedSoAKeyHolderCanTellWhose() =
        testApplication {
            val (subject, vouchers) = confirmedSubject()
            settle()
            assertTrue(personBadge(subject))
            application { install(config()) }

            val confirmationId = firstVouchId(subject)
            val voidOne =
                client.post("/v1/admin/met-in-person/confirmations/$confirmationId/void") {
                    adminHeaders("senior-1")
                    jsonBody("""{"subjectUserId":"$subject"}""")
                }
            assertEquals(HttpStatusCode.OK, voidOne.status, voidOne.bodyAsText())
            assertEquals(1, auditRows("met_in_person.void_confirmation"))
            assertTrue(personBadge(subject), "a void changes the badge only through later batches")
            settle()
            assertFalse(personBadge(subject))

            val undo =
                client.post("/v1/admin/met-in-person/confirmations/$confirmationId/void") {
                    adminHeaders("senior-1")
                    jsonBody("""{"subjectUserId":"$subject","voided":false}""")
                }
            assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())
            assertEquals(1, auditRows("met_in_person.unvoid_confirmation"))
            settle()
            assertTrue(personBadge(subject))

            val voidVoucher =
                client.post("/v1/admin/met-in-person/vouchers/void") {
                    adminHeaders("senior-1")
                    jsonBody("""{"voucherUserId":"${vouchers[1]}"}""")
                }
            assertEquals(HttpStatusCode.OK, voidVoucher.status, voidVoucher.bodyAsText())
            assertTrue(voidVoucher.bodyAsText().contains("\"affected\":1"))
            val auditText = allAuditText()
            assertTrue(auditText.contains(tokens.auditVoucher(vouchers[1])), "a key holder can tell whose were voided")
            vouchers.forEach { assertFalse(auditText.contains(it.toString())) }

            val freeze =
                client.post("/v1/admin/met-in-person/users/$subject/freeze") {
                    adminHeaders("senior-1")
                    jsonBody("""{"frozen":true}""")
                }
            assertEquals(HttpStatusCode.OK, freeze.status, freeze.bodyAsText())
            assertEquals(1, auditRows("met_in_person.freeze"))
            assertEquals(1, fx.count("SELECT count(*) FROM vouch_badges WHERE subject_user_id = ? AND frozen_at IS NOT NULL", subject))
        }

    @Test
    fun aFrozenBadgeStaysOffAtEveryBatch() {
        runBlocking {
            val (subject, _) = confirmedSubject()
            settle()
            assertTrue(personBadge(subject))
            admin.setFrozen("senior-1", subject, frozen = true, reason = "ring")
            assertFalse(personBadge(subject))
            settle()
            assertFalse(personBadge(subject))
            admin.setFrozen("senior-1", subject, frozen = false, reason = "cleared")
            settle()
            assertTrue(personBadge(subject))
        }
    }

    @Test
    fun aVoidIsInvisibleToTheVoucher() {
        runBlocking {
            val (subject, vouchers) = confirmedSubject()
            val conversation = dataSource.directConversation(vouchers[0], subject).toString()
            val before = service.offers(vouchers[0], conversation)
            admin.setVoucherVoided("senior-1", vouchers[0], voided = true, reason = "farm")
            assertEquals(before, service.offers(vouchers[0], conversation))
        }
    }

    private fun auditMetadata(action: String): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT metadata::text FROM admin_audit_log WHERE action = ? ORDER BY created_at").use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    @Test
    fun theViewAuditHoldsNoCountsAndTheSubjectRefIsItsOwnDomain() {
        runBlocking {
            val (subject, _) = confirmedSubject()
            admin.view("senior-1", subject, reason = "review")
            assertEquals(listOf("{}"), auditMetadata("met_in_person.view").map { it.replace(" ", "") })

            admin.setConfirmationVoided("senior-1", subject, firstVouchId(subject), voided = true, reason = "farm")
            val metadata = auditMetadata("met_in_person.void_confirmation").single()
            assertTrue(metadata.contains(tokens.auditSubject(subject)), metadata)
            assertFalse(allAuditText().contains(tokens.subject(subject)), "never the token stored in vouches")
        }
    }

    @Test
    fun undoingAVoucherVoidLeavesIndividuallyVoidedRowsVoided() {
        runBlocking {
            val voucher = fx.member("Voucher")
            val first = fx.member("First")
            val second = fx.member("Second")
            listOf(first, second).forEach { subject ->
                service.confirm(voucher, fx.talked(voucher, subject).toString(), ConfirmMetInPersonRequestDto(subject.toString()))
            }
            admin.setConfirmationVoided("senior-1", first, firstVouchId(first), voided = true, reason = "this one")

            assertEquals(1, admin.setVoucherVoided("senior-1", voucher, voided = true, reason = "farm").affected)
            assertEquals(1, admin.setVoucherVoided("senior-1", voucher, voided = false, reason = "cleared").affected)

            fun voided(subject: UUID): Int =
                fx.count("SELECT count(*) FROM vouches WHERE subject_token = ? AND voided_at IS NOT NULL", tokens.subject(subject))
            assertEquals(1, voided(first), "voided on its own: stays voided")
            assertEquals(0, voided(second))
            admin.setVoucherVoided("senior-1", voucher, voided = true, reason = "again")
            val notWholeDays =
                fx.count(
                    "SELECT count(*) FROM vouches WHERE voided_at IS NOT NULL " +
                        "AND voided_at <> date_trunc('day', voided_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'",
                )
            assertEquals(0, notWholeDays, "a void keeps the day only")
        }
    }

    @Test
    fun unfreezingSomeoneWithoutABadgeRowWritesNoRow() {
        runBlocking {
            val member = fx.member("Nobody")
            admin.setFrozen("senior-1", member, frozen = false, reason = "mistake")
            assertEquals(0, fx.count("SELECT count(*) FROM vouch_badges WHERE subject_user_id = ?", member))
            admin.setFrozen("senior-1", member, frozen = true, reason = "ring")
            admin.setFrozen("senior-1", member, frozen = false, reason = "cleared")
            assertEquals(0, fx.count("SELECT count(*) FROM vouch_badges WHERE subject_user_id = ?", member), "nothing published, so no row")
        }
    }

    private companion object {
        const val TOKEN = "met-in-person-admin-token"
    }
}

internal fun javax.sql.DataSource.directConversation(
    a: UUID,
    b: UUID,
): UUID =
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT conversation_id FROM direct_conversations
                WHERE LEAST(user_a_id, user_b_id) = LEAST(?::uuid, ?::uuid)
                  AND GREATEST(user_a_id, user_b_id) = GREATEST(?::uuid, ?::uuid)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, a)
                statement.setObject(2, b)
                statement.setObject(3, a)
                statement.setObject(4, b)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getObject(1, UUID::class.java)
                }
            }
    }
