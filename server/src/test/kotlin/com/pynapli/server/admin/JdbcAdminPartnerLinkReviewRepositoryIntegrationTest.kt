package com.pynapli.server.admin

import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcAdminPartnerLinkReviewRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun readsPartnerLinksVisibilityReportContextAndWritesAudit() =
        runBlocking {
            val repository = JdbcAdminPartnerLinkReviewRepository(dataSource)
            val userId = createUser()
            val activePartnerId = createUser()
            val pastPartnerId = createUser()
            val reporterId = createUser()
            val activeLinkId = UUID.randomUUID()
            val pastLinkId = UUID.randomUUID()

            dataSource.updatePartnerVisibility(userId, "matches_only")
            dataSource.insertLink(
                linkId = activeLinkId,
                userId = activePartnerId,
                linkedUserId = userId,
                status = "active",
                createdAt = Instant.parse("2026-06-01T00:00:00Z"),
                acceptedAt = Instant.parse("2026-06-01T01:00:00Z"),
                unlinkedAt = null,
            )
            dataSource.insertLink(
                linkId = pastLinkId,
                userId = userId,
                linkedUserId = pastPartnerId,
                status = "unlinked",
                createdAt = Instant.parse("2026-05-01T00:00:00Z"),
                acceptedAt = Instant.parse("2026-05-01T01:00:00Z"),
                unlinkedAt = Instant.parse("2026-05-20T00:00:00Z"),
            )
            dataSource.insertReport(reporterUserId = reporterId, reportedUserId = userId)
            dataSource.insertReport(reporterUserId = reporterId, reportedUserId = userId)
            dataSource.insertReport(reporterUserId = reporterId, reportedUserId = activePartnerId)

            val facts = repository.review(userId)

            assertNotNull(facts)
            assertEquals("matches_only", facts.partnerVisibility)
            assertEquals(2, facts.links.size)
            assertEquals(activePartnerId, facts.links.first { it.linkId == activeLinkId }.otherUserId)
            assertEquals("received", facts.links.first { it.linkId == activeLinkId }.direction)
            assertEquals(pastPartnerId, facts.links.first { it.linkId == pastLinkId }.otherUserId)
            assertEquals("initiated", facts.links.first { it.linkId == pastLinkId }.direction)
            assertEquals(2, facts.reportsAgainstUserCount)
            assertEquals(1, facts.reportsAgainstCurrentPartnerCount)

            repository.recordReviewAudit(actorId = "ops-1", userId = userId, reason = "support review")
            assertEquals(1, dataSource.adminAuditCount("partner_link_review", userId))
        }

    @Test
    fun unknownUserReturnsNull() =
        runBlocking {
            val repository = JdbcAdminPartnerLinkReviewRepository(dataSource)

            assertNull(repository.review(UUID.randomUUID()))
        }

    private suspend fun createUser(): UUID {
        val userId = UUID.randomUUID()
        JdbcAuthRepository(dataSource).createEmailUser(
            userId = userId,
            identityId = UUID.randomUUID(),
            emailHash = "email-$userId",
            passwordHash = PasswordHash(hash = "hash", salt = "salt", iterations = 1),
            birthdate = LocalDate.parse("1990-05-13"),
            displayAge = 36,
            adultConfirmedAt = Instant.parse("2026-05-13T12:00:00Z"),
            termsAcceptedAt = Instant.parse("2026-05-13T12:00:00Z"),
            termsVersion = "2026-05-13",
            privacyAcceptedAt = Instant.parse("2026-05-13T12:00:00Z"),
            privacyVersion = "2026-05-13",
        )
        return userId
    }
}

private fun DataSource.updatePartnerVisibility(
    userId: UUID,
    visibility: String,
) {
    connection.use { connection ->
        connection
            .prepareStatement("UPDATE discovery_settings SET partner_visibility = ? WHERE user_id = ?")
            .use { statement ->
                statement.setString(1, visibility)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertLink(
    linkId: UUID,
    userId: UUID,
    linkedUserId: UUID,
    status: String,
    createdAt: Instant,
    acceptedAt: Instant?,
    unlinkedAt: Instant?,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO user_profile_links (
                    id, user_id, linked_user_id, status, created_at, accepted_at, unlinked_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, linkId)
                statement.setObject(2, userId)
                statement.setObject(3, linkedUserId)
                statement.setString(4, status)
                statement.setTimestamp(5, Timestamp.from(createdAt))
                statement.setTimestamp(6, acceptedAt?.let(Timestamp::from))
                statement.setTimestamp(7, unlinkedAt?.let(Timestamp::from))
                statement.executeUpdate()
            }
    }
}

private fun DataSource.insertReport(
    reporterUserId: UUID,
    reportedUserId: UUID,
) {
    connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO safety_reports (
                    id, reporter_user_id, reported_user_id, target_type, target_id, category, note, status
                )
                VALUES (?, ?, ?, 'profile', ?, 'other', 'admin partner-link review test', 'open')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, reporterUserId)
                statement.setObject(3, reportedUserId)
                statement.setString(4, reportedUserId.toString())
                statement.executeUpdate()
            }
    }
}

private fun DataSource.adminAuditCount(
    action: String,
    targetId: UUID,
): Int =
    connection.use { connection ->
        connection
            .prepareStatement("SELECT count(*) FROM admin_audit_log WHERE action = ? AND target_id = ?")
            .use { statement ->
                statement.setString(1, action)
                statement.setObject(2, targetId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
    }
