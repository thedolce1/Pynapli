package com.pynapli.server.safety

import com.pynapli.server.moderation.JdbcAdminAppealRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppealRepositoryIntegrationTest {
    private val dataSource: DataSource = PynapliTestDatabase.dataSource

    @Before
    fun reset() {
        PynapliTestDatabase.reset()
    }

    @Test
    fun mediaRemovalAppealRequiresOwnedRemovedRecentMediaAndPreventsDuplicate() =
        runBlocking {
            val owner = UUID.fromString("00000000-0000-0000-0000-00000000c001")
            val other = UUID.fromString("00000000-0000-0000-0000-00000000c002")
            val mediaAssetId = UUID.fromString("00000000-0000-0000-0000-00000000c101")
            val repository = JdbcAppealRepository(dataSource)
            dataSource.insertUser(owner)
            dataSource.insertUser(other)
            dataSource.insertRemovedMediaAsset(owner, mediaAssetId)

            val created =
                repository.createAppeal(
                    userId = owner,
                    actionType = "media_removal",
                    targetType = "media",
                    targetId = mediaAssetId.toString(),
                    relatedReportId = null,
                    reason = "This private album item was removed by mistake.",
                )

            assertIs<CreateAppealResult.Created>(created)
            assertEquals(1, repository.listAppeals(owner, limit = 10).size)

            val duplicate =
                repository.createAppeal(
                    userId = owner,
                    actionType = "media_removal",
                    targetType = "media",
                    targetId = mediaAssetId.toString(),
                    relatedReportId = null,
                    reason = "Second copy.",
                )
            assertEquals(CreateAppealResult.DuplicateOpenAppeal, duplicate)

            val wrongOwner =
                repository.createAppeal(
                    userId = other,
                    actionType = "media_removal",
                    targetType = "media",
                    targetId = mediaAssetId.toString(),
                    relatedReportId = null,
                    reason = "Not mine.",
                )
            assertEquals(CreateAppealResult.TargetNotAppealable, wrongOwner)
        }

    @Test
    fun staleSuspensionAppealIsRejected() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-00000000c011")
            val repository = JdbcAppealRepository(dataSource)
            dataSource.insertUser(userId, status = "suspended")
            dataSource.backdateUserUpdate(userId, days = 31)

            val result =
                repository.createAppeal(
                    userId = userId,
                    actionType = "account_suspension",
                    targetType = "account",
                    targetId = null,
                    relatedReportId = null,
                    reason = "I want this reviewed.",
                )

            assertEquals(CreateAppealResult.TargetNotAppealable, result)
        }

    @Test
    fun adminDecisionUpdatesAppealAndWritesAudit() =
        runBlocking {
            val userId = UUID.fromString("00000000-0000-0000-0000-00000000c021")
            val appealRepository = JdbcAppealRepository(dataSource)
            val adminRepository = JdbcAdminAppealRepository(dataSource)
            dataSource.insertUser(userId)
            dataSource.insertRiskState(userId, tier = "quarantine", score = 70)

            val created =
                assertIs<CreateAppealResult.Created>(
                    appealRepository.createAppeal(
                        userId = userId,
                        actionType = "risk_restriction",
                        targetType = "account",
                        targetId = null,
                        relatedReportId = null,
                        reason = "My behavior was normal.",
                    ),
                )

            val listed =
                adminRepository.listAppeals(
                    status = "open",
                    limit = 10,
                    actorId = "senior-mod-1",
                    reason = "appeal queue",
                )
            assertEquals(created.appeal.id, listed.single().appealId)
            assertEquals(1, dataSource.adminAuditCount("appeals.list"))

            val action =
                adminRepository.decideAppeal(
                    appealId = created.appeal.id,
                    status = "restored",
                    actorId = "senior-mod-1",
                    reason = "false positive",
                    adminNote = "We restored access.",
                )

            assertEquals("restored", action?.status)
            assertEquals(1, dataSource.adminAuditCount("appeal.restored"))
            val appeal = appealRepository.listAppeals(userId, limit = 10).single()
            assertEquals("restored", appeal.status)
            assertEquals("We restored access.", appeal.adminNote)
        }

    private fun DataSource.insertUser(
        userId: UUID,
        status: String = "active",
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, status)
                    VALUES (?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, status)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.backdateUserUpdate(
        userId: UUID,
        days: Int,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE users
                    SET updated_at = now() - (? * INTERVAL '1 day')
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, days)
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertRemovedMediaAsset(
        ownerUserId: UUID,
        mediaAssetId: UUID,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO media_assets (
                        id,
                        owner_user_id,
                        media_type,
                        storage_provider,
                        storage_bucket,
                        storage_object_key,
                        content_type,
                        byte_size,
                        moderation_status,
                        processing_status
                    )
                    VALUES (?, ?, 'image', 'gcs', 'pynapli-test', ?, 'image/jpeg', 1024, 'removed', 'ready')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, mediaAssetId)
                    statement.setObject(2, ownerUserId)
                    statement.setString(3, "media/$mediaAssetId")
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertRiskState(
        userId: UUID,
        tier: String,
        score: Int,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO account_risk_states (user_id, score, tier, last_event_at, updated_at)
                    VALUES (?, ?, ?, now(), now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setInt(2, score)
                    statement.setString(3, tier)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.adminAuditCount(action: String): Int =
        connection.use { connection ->
            connection
                .prepareStatement("SELECT count(*) FROM admin_audit_log WHERE action = ?")
                .use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
}
