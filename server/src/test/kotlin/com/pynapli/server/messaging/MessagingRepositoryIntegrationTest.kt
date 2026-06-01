package com.pynapli.server.messaging

import com.pynapli.server.moderation.JdbcMessageModerationRepository
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessagingRepositoryIntegrationTest {
    private lateinit var dataSource: HikariDataSource

    @Before
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 2
                    poolName = "pynapli-messaging-test"
                },
            )

        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }
    }

    @After
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun swipeOriginConversationRequiresMatch() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000801")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000802")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)

            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "swipe",
                )

            assertNull(conversation)
        }

    @Test
    fun swipeOriginConversationIsAllowedForMutualMatch() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000811")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000812")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            dataSource.insertMatch(viewer, target)
            val repository = JdbcMessagingRepository(dataSource)

            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "swipe",
                )

            val created = assertNotNull(conversation)
            assertEquals("swipe", created.origin)
            assertEquals(setOf(viewer, target), created.participants.map { it.userId }.toSet())
        }

    @Test
    fun introNoteOriginIsRejectedUntilEntitlementExists() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000821")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000822")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)

            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "intro_note",
                )

            assertNull(conversation)
        }

    @Test
    fun gridOriginConversationRespectsMatchesOnlyPolicy() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000831")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000832")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            dataSource.setGridMessagePolicy(target, allowGridMessages = true, policy = "matches_only")
            val repository = JdbcMessagingRepository(dataSource)

            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )

            assertNull(conversation)
        }

    @Test
    fun gridOriginConversationAllowsMatchesOnlyPolicyForMutualMatch() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000841")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000842")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            dataSource.setGridMessagePolicy(target, allowGridMessages = true, policy = "matches_only")
            dataSource.insertMatch(viewer, target)
            val repository = JdbcMessagingRepository(dataSource)

            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )

            val created = assertNotNull(conversation)
            assertEquals("grid", created.origin)
        }

    @Test
    fun heldFirstMessageIsQueuedAndHiddenFromRecipientThreadList() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000851")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000852")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )!!

            val held =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Find me at https://example.com",
                    moderationStatus = MessageModerationStatus.HELD,
                    bodyFingerprint = "held-message-fingerprint",
                    safetyReason = MessageSafetyReason.LINK_FIRST_MESSAGE,
                )!!

            assertEquals("held", held.status)
            assertEquals("held_for_review", held.deliveryStatus)
            assertEquals(1, dataSource.countMessageReviewQueueRows(held.messageId))
            assertEquals(1, repository.listConversations(viewer, limit = 10).size)
            assertEquals(emptyList(), repository.listConversations(target, limit = 10))
            assertEquals(1, repository.getConversationDetail(viewer, conversation.conversationId, limit = 10)?.messages?.size)
            assertEquals(emptyList(), repository.getConversationDetail(target, conversation.conversationId, limit = 10)?.messages)
        }

    @Test
    fun resendingWithSameIdempotencyKeyReturnsOriginalAndCreatesNoDuplicate() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000871")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000872")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )!!

            val first =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Drinks this week?",
                    clientIdempotencyKey = "retry-key-1",
                )!!
            // Simulated retry of a send whose response was lost: same key, same body.
            val retry =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Drinks this week?",
                    clientIdempotencyKey = "retry-key-1",
                )!!

            assertEquals(first.messageId, retry.messageId, "Retry must return the original message")
            assertEquals(
                1,
                dataSource.countMessagesInConversation(conversation.conversationId),
                "Retry must not create a duplicate row",
            )
            assertEquals(
                1,
                repository.getConversationDetail(viewer, conversation.conversationId, limit = 10)?.messages?.size,
            )
        }

    @Test
    fun differentIdempotencyKeysCreateDistinctMessages() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000881")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000882")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )!!

            val first =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Same text",
                    clientIdempotencyKey = "key-a",
                )!!
            val second =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Same text",
                    clientIdempotencyKey = "key-b",
                )!!

            assertNotEquals(first.messageId, second.messageId)
            assertEquals(2, dataSource.countMessagesInConversation(conversation.conversationId))
        }

    @Test
    fun releasedHeldMessageSurfacesForRecipientAndWritesAuditRows() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000861")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000862")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)
            val moderationRepository = JdbcMessageModerationRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )!!
            val held =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Find me at https://example.com",
                    moderationStatus = MessageModerationStatus.HELD,
                    bodyFingerprint = "released-message-fingerprint",
                    safetyReason = MessageSafetyReason.LINK_FIRST_MESSAGE,
                )!!

            val queueItems =
                moderationRepository.listOpenMessageReviews(
                    limit = 10,
                    actorId = "moderator-1",
                    reason = "reviewing held first messages",
                )
            assertEquals(listOf(held.messageId), queueItems.map { it.messageId })
            assertEquals(emptyList(), repository.listConversations(target, limit = 10))

            val action =
                moderationRepository.releaseHeldMessage(
                    messageId = held.messageId,
                    actorId = "moderator-1",
                    reason = "false positive",
                )

            assertNotNull(action)
            assertEquals("visible", action.messageStatus)
            assertEquals("released", action.queueStatus)
            assertEquals("released", dataSource.messageReviewQueueStatus(held.messageId))
            assertEquals(1, repository.listConversations(target, limit = 10).size)
            assertEquals(
                listOf("visible"),
                repository
                    .getConversationDetail(target, conversation.conversationId, limit = 10)
                    ?.messages
                    ?.map { it.status },
            )
            assertEquals(1, dataSource.countAdminAuditRows("message_review_queue.list"))
            assertEquals(1, dataSource.countAdminAuditRows("message_review.release"))
        }

    @Test
    fun removedHeldMessageStaysHiddenAndWritesAuditRow() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000871")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000872")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)
            val moderationRepository = JdbcMessageModerationRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )!!
            val held =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Find me at https://example.com",
                    moderationStatus = MessageModerationStatus.HELD,
                    bodyFingerprint = "removed-message-fingerprint",
                    safetyReason = MessageSafetyReason.LINK_FIRST_MESSAGE,
                )!!

            val action =
                moderationRepository.removeHeldMessage(
                    messageId = held.messageId,
                    actorId = "moderator-1",
                    reason = "external link spam",
                )

            assertNotNull(action)
            assertEquals("removed", action.messageStatus)
            assertEquals("removed", action.queueStatus)
            assertEquals("removed", dataSource.messageReviewQueueStatus(held.messageId))
            assertEquals(emptyList(), repository.listConversations(target, limit = 10))
            assertEquals(
                emptyList(),
                repository.getConversationDetail(viewer, conversation.conversationId, limit = 10)?.messages,
            )
            assertEquals(
                emptyList(),
                repository.getConversationDetail(target, conversation.conversationId, limit = 10)?.messages,
            )
            assertEquals(1, dataSource.countAdminAuditRows("message_review.remove"))
        }

    @Test
    fun deletingOwnVisibleMessageKeepsPlaceholderForParticipants() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000875")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000876")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )!!
            val message =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Delete this",
                    moderationStatus = MessageModerationStatus.VISIBLE,
                    bodyFingerprint = "delete-visible-message",
                    safetyReason = null,
                )!!

            val deleted = repository.deleteOwnMessage(viewer, message.messageId)
            val viewerMessages = repository.getConversationDetail(viewer, conversation.conversationId, limit = 10)?.messages
            val targetMessages = repository.getConversationDetail(target, conversation.conversationId, limit = 10)?.messages

            assertNotNull(deleted)
            assertEquals("", deleted.body)
            assertEquals("deleted", deleted.deliveryStatus)
            assertNotNull(deleted.deletedAt)
            assertEquals(listOf(""), viewerMessages?.map { it.body })
            assertEquals(listOf("deleted"), viewerMessages?.map { it.deliveryStatus })
            assertEquals(listOf(""), targetMessages?.map { it.body })
            assertEquals(listOf("deleted"), targetMessages?.map { it.deliveryStatus })
            assertNull(repository.deleteOwnMessage(target, message.messageId))
        }

    @Test
    fun deletingHeldMessageIsRejectedSoModerationReviewRemainsIntact() =
        runBlocking {
            val viewer = UUID.fromString("00000000-0000-0000-0000-000000000877")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000878")
            dataSource.insertUserWithProfile(viewer, "Viewer")
            dataSource.insertUserWithProfile(target, "Target")
            val repository = JdbcMessagingRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = viewer,
                    targetUserId = target,
                    origin = "grid",
                )!!
            val held =
                repository.sendMessage(
                    userId = viewer,
                    conversationId = conversation.conversationId,
                    body = "Find me at https://example.com",
                    moderationStatus = MessageModerationStatus.HELD,
                    bodyFingerprint = "held-delete-attempt",
                    safetyReason = MessageSafetyReason.LINK_FIRST_MESSAGE,
                )!!

            assertNull(repository.deleteOwnMessage(viewer, held.messageId))
            assertEquals(1, dataSource.countMessageReviewQueueRows(held.messageId))
        }

    @Test
    fun partnerChatInviteRequiresPartnerAndThirdPartyConsent() =
        runBlocking {
            val inviter = UUID.fromString("00000000-0000-0000-0000-000000000881")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000882")
            val partner = UUID.fromString("00000000-0000-0000-0000-000000000883")
            dataSource.insertUserWithProfile(inviter, "Inviter")
            dataSource.insertUserWithProfile(target, "Target")
            dataSource.insertUserWithProfile(partner, "Partner")
            val repository = JdbcMessagingRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = inviter,
                    targetUserId = target,
                    origin = "grid",
                )!!

            val invited =
                repository.inviteLinkedPartner(
                    inviterUserId = inviter,
                    conversationId = conversation.conversationId,
                    partnerUserId = partner,
                ) as PartnerInviteResult.Invited

            assertEquals("pending", invited.conversation.partnerInvite?.partnerStatus)
            assertEquals("pending", invited.conversation.partnerInvite?.thirdPartyStatus)

            val partnerAccepted = repository.acceptPartnerInvite(partner, conversation.conversationId)!!
            assertEquals("accepted", partnerAccepted.conversation.partnerInvite?.partnerStatus)
            assertEquals("pending", partnerAccepted.conversation.partnerInvite?.thirdPartyStatus)
            assertNull(
                repository.sendMessage(
                    userId = partner,
                    conversationId = conversation.conversationId,
                    body = "Too soon",
                    moderationStatus = MessageModerationStatus.VISIBLE,
                    bodyFingerprint = null,
                    safetyReason = null,
                ),
            )

            val targetApproved = repository.acceptPartnerInvite(target, conversation.conversationId)!!
            assertEquals(null, targetApproved.conversation.partnerInvite)
            assertEquals(
                ConversationParticipantStatus.ACTIVE,
                targetApproved.conversation.participants
                    .first { it.userId == partner }
                    .status,
            )
            assertNotNull(
                repository.sendMessage(
                    userId = partner,
                    conversationId = conversation.conversationId,
                    body = "Joining",
                    moderationStatus = MessageModerationStatus.VISIBLE,
                    bodyFingerprint = null,
                    safetyReason = null,
                ),
            )
            Unit
        }

    @Test
    fun thirdPartyDeclineStartsPartnerInviteCooldown() =
        runBlocking {
            val inviter = UUID.fromString("00000000-0000-0000-0000-000000000891")
            val target = UUID.fromString("00000000-0000-0000-0000-000000000892")
            val partner = UUID.fromString("00000000-0000-0000-0000-000000000893")
            dataSource.insertUserWithProfile(inviter, "Inviter")
            dataSource.insertUserWithProfile(target, "Target")
            dataSource.insertUserWithProfile(partner, "Partner")
            val repository = JdbcMessagingRepository(dataSource)
            val conversation =
                repository.getOrCreateDirectConversation(
                    viewerUserId = inviter,
                    targetUserId = target,
                    origin = "grid",
                )!!
            repository.inviteLinkedPartner(
                inviterUserId = inviter,
                conversationId = conversation.conversationId,
                partnerUserId = partner,
            )

            assertEquals(true, repository.declinePartnerInvite(target, conversation.conversationId))

            val retry =
                repository.inviteLinkedPartner(
                    inviterUserId = inviter,
                    conversationId = conversation.conversationId,
                    partnerUserId = partner,
                )
            assertEquals(PartnerInviteResult.CooldownActive, retry)
            assertEquals(null, repository.getConversationDetail(partner, conversation.conversationId, limit = 10))
        }

    private fun DataSource.insertUserWithProfile(
        userId: UUID,
        displayName: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (id, status)
                    VALUES (?, 'active')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO profiles (user_id, display_name, moderation_status, visibility_status)
                    VALUES (?, ?, 'approved', 'visible')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, displayName)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO discovery_settings (user_id)
                    VALUES (?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.insertMatch(
        userA: UUID,
        userB: UUID,
    ) {
        val (left, right) = if (userA.compareTo(userB) < 0) userA to userB else userB to userA
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO matches (id, user_a, user_b)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, left)
                    statement.setObject(3, right)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.setGridMessagePolicy(
        userId: UUID,
        allowGridMessages: Boolean,
        policy: String,
    ) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE discovery_settings
                    SET allow_grid_messages = ?,
                        grid_message_policy = ?
                    WHERE user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setBoolean(1, allowGridMessages)
                    statement.setString(2, policy)
                    statement.setObject(3, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun DataSource.countMessagesInConversation(conversationId: UUID): Int =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*) AS row_count
                    FROM messages
                    WHERE conversation_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, conversationId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt("row_count")
                    }
                }
        }

    private fun DataSource.countMessageReviewQueueRows(messageId: UUID): Int =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*) AS row_count
                    FROM message_review_queue
                    WHERE message_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, messageId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt("row_count")
                    }
                }
        }

    private fun DataSource.messageReviewQueueStatus(messageId: UUID): String? =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT status
                    FROM message_review_queue
                    WHERE message_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, messageId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("status") else null
                    }
                }
        }

    private fun DataSource.countAdminAuditRows(action: String): Int =
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*) AS row_count
                    FROM admin_audit_log
                    WHERE action = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt("row_count")
                    }
                }
        }

    companion object {
        private val postgisImage =
            DockerImageName
                .parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres")

        private val postgres =
            PostgreSQLContainer<Nothing>(postgisImage).apply {
                withDatabaseName("pynapli_messaging_test")
                withUsername("pynapli")
                withPassword("pynapli_test_password")
            }

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            postgres.start()
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            postgres.stop()
        }
    }
}
