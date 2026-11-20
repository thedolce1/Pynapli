package com.pynapli.server.messaging

import com.pynapli.server.activity.ActivityEventRecord
import com.pynapli.server.activity.ActivityFeedQuery
import com.pynapli.server.activity.ActivityRepository
import com.pynapli.server.activity.ActivityService
import com.pynapli.server.activity.ActivitySummaryRecord
import com.pynapli.server.activity.AlbumRequestEventRecord
import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationOutboxRecord
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.AccountRiskStateRecord
import com.pynapli.server.safety.MessageReviewRiskSignalPublisher
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionRecord
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.testsupport.StaticSubscriptionRepository
import com.pynapli.server.testsupport.denyingRateLimitService
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MessageDeltaType
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessagingServiceTest {
    @Test
    fun createDirectConversationReturnsConversationShell() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val service = MessagingService(InMemoryMessagingRepository(setOf(targetId)))

            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString(), origin = "grid"),
                )

            assertEquals("direct", detail.conversation.kind)
            assertEquals("grid", detail.conversation.origin)
            assertEquals(2, detail.conversation.participants.size)
            assertEquals(emptyList(), detail.messages)
        }

    @Test
    fun createDirectConversationRejectsSelfTarget() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = MessagingService(InMemoryMessagingRepository(setOf(userId)))

            val error =
                assertFailsWith<MessagingException> {
                    service.createDirectConversation(
                        userId,
                        CreateDirectConversationRequestDto(targetUserId = userId.toString()),
                    )
                }

            assertEquals("INVALID_CONVERSATION_TARGET", error.code)
        }

    @Test
    fun createDirectConversationRespectsRecipientMessagingSetting() =
        runBlocking {
            val viewerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            val service = MessagingService(InMemoryMessagingRepository(allowedTargets = emptySet()))

            val error =
                assertFailsWith<MessagingException> {
                    service.createDirectConversation(
                        viewerId,
                        CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                    )
                }

            assertEquals("MESSAGING_NOT_ALLOWED", error.code)
        }

    @Test
    fun createDirectConversationRejectsRiskRestrictedViewer() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000011")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000012")
            val service =
                MessagingService(
                    repository = InMemoryMessagingRepository(setOf(targetId)),
                    riskPolicyService = restrictedRiskPolicy(),
                )

            val error =
                assertFailsWith<MessagingException> {
                    service.createDirectConversation(
                        viewerId,
                        CreateDirectConversationRequestDto(targetUserId = targetId.toString(), origin = "grid"),
                    )
                }

            assertEquals("RISK_RESTRICTED", error.code)
        }

    @Test
    fun sendMessageStoresTrimmedBody() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val notifications = RecordingNotificationPublisher()
            val service = MessagingService(repository, notificationPublisher = notifications)
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )

            val message =
                service.sendMessage(
                    viewerId,
                    detail.conversation.conversationId,
                    SendMessageRequestDto(body = "  Hey there  "),
                )

            assertEquals("Hey there", message.body)
            val refreshed = service.getConversationDetail(viewerId, detail.conversation.conversationId)
            assertEquals(1, refreshed.messages.size)
            assertEquals("Hey there", refreshed.messages.first().body)
            assertEquals(NotificationKind.MESSAGE, notifications.events.single().kind)
            assertEquals(targetId, notifications.events.single().recipientUserId)
        }

    @Test
    fun sendMessagePublishesRealtimeDeltaToActiveParticipants() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000051")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000052")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val broadcaster = MessageBroadcaster()
            val service =
                MessagingService(
                    repository = repository,
                    messageBroadcaster = broadcaster,
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val deltaJob = async { broadcaster.deltas.take(2).toList() }
            delay(10)

            val message =
                service.sendMessage(
                    viewerId,
                    detail.conversation.conversationId,
                    SendMessageRequestDto(body = "Hey there"),
                )

            val deltas = withTimeout(1_000) { deltaJob.await() }
            assertEquals(setOf(viewerId, targetId), deltas.map { it.recipientUserId }.toSet())
            assertEquals(listOf(MessageDeltaType.MESSAGE_CREATED, MessageDeltaType.MESSAGE_CREATED), deltas.map { it.type })
            assertEquals(setOf(UUID.fromString(message.messageId)), deltas.mapNotNull { it.messageId }.toSet())
        }

    @Test
    fun listConversationsReturnsThreadsWithLastMessage() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service = MessagingService(repository)
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Hey there"))

            val response = service.listConversations(viewerId)

            assertEquals(1, response.items.size)
            assertEquals(detail.conversation.conversationId, response.items.first().conversationId)
            assertEquals(
                "Hey there",
                response.items
                    .first()
                    .lastMessage
                    ?.body,
            )
        }

    @Test
    fun markConversationReadClearsUnreadCount() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service = MessagingService(repository)
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            repository.sendMessage(
                userId = targetId,
                conversationId = UUID.fromString(detail.conversation.conversationId),
                body = "Incoming",
                moderationStatus = MessageModerationStatus.VISIBLE,
                bodyFingerprint = null,
                safetyReason = null,
                messageIdFactory = UUID::randomUUID,
            )

            assertEquals(
                1,
                service
                    .listConversations(viewerId)
                    .items
                    .first()
                    .unreadCount,
            )

            val readConversation = service.markConversationRead(viewerId, detail.conversation.conversationId)

            assertEquals(0, readConversation.unreadCount)
            assertEquals(
                0,
                service
                    .listConversations(viewerId)
                    .items
                    .first()
                    .unreadCount,
            )
        }

    @Test
    fun senderMessageDeliveryStatusMovesFromSentToRead() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service = MessagingService(repository)
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Hey there"))

            val beforeRead = service.getConversationDetail(viewerId, detail.conversation.conversationId)

            assertEquals("sent", beforeRead.messages.first().deliveryStatus)

            service.markConversationRead(targetId, detail.conversation.conversationId)
            val afterRead = service.getConversationDetail(viewerId, detail.conversation.conversationId)

            assertEquals("read", afterRead.messages.first().deliveryStatus)
        }

    @Test
    fun readReceiptsAreMaskedForFreeSender() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000061")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000062")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service =
                MessagingService(
                    repository = repository,
                    subscriptionService =
                        subscriptionService(
                            mapOf(targetId to SubscriptionTier.SELECT),
                        ),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Hey there"))

            service.markConversationRead(targetId, detail.conversation.conversationId)
            val afterRead = service.getConversationDetail(viewerId, detail.conversation.conversationId)

            assertEquals("sent", afterRead.messages.first().deliveryStatus)
        }

    @Test
    fun readReceiptsRequireRecipientReceiptEntitlement() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000063")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000064")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service =
                MessagingService(
                    repository = repository,
                    subscriptionService =
                        subscriptionService(
                            mapOf(viewerId to SubscriptionTier.SOCIAL),
                        ),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Hey there"))

            service.markConversationRead(targetId, detail.conversation.conversationId)
            val afterRead = service.getConversationDetail(viewerId, detail.conversation.conversationId)

            assertEquals("sent", afterRead.messages.first().deliveryStatus)
        }

    @Test
    fun setConversationExpiryRequiresPaidTier() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
            val targetId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service =
                MessagingService(
                    repository = repository,
                    subscriptionService = subscriptionService(mapOf(viewerId to SubscriptionTier.FREE)),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )

            val error =
                assertFailsWith<MessagingException> {
                    service.setConversationExpiry(viewerId, detail.conversation.conversationId, 86_400)
                }
            assertEquals("ENTITLEMENT_REQUIRED", error.code)
        }

    @Test
    fun setConversationExpiryRejectsUnsupportedTimer() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
            val targetId = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service =
                MessagingService(
                    repository = repository,
                    subscriptionService = subscriptionService(mapOf(viewerId to SubscriptionTier.SOCIAL)),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )

            val error =
                assertFailsWith<MessagingException> {
                    service.setConversationExpiry(viewerId, detail.conversation.conversationId, 99)
                }
            assertEquals("INVALID_MESSAGE_EXPIRY", error.code)
        }

    @Test
    fun setConversationExpiryStoresTimerAndRecordsSystemMessage() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-0000000000a5")
            val targetId = UUID.fromString("00000000-0000-0000-0000-0000000000a6")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val broadcaster = MessageBroadcaster()
            val service =
                MessagingService(
                    repository = repository,
                    messageBroadcaster = broadcaster,
                    subscriptionService = subscriptionService(mapOf(viewerId to SubscriptionTier.SOCIAL)),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val createdDeltaJob = async { broadcaster.deltas.first { it.type == MessageDeltaType.MESSAGE_CREATED } }
            delay(10)

            val conversation = service.setConversationExpiry(viewerId, detail.conversation.conversationId, 86_400)

            assertEquals(86_400, conversation.messageExpirySeconds)
            assertEquals(86_400, conversation.effectiveMessageExpirySeconds)
            withTimeout(1_000) { createdDeltaJob.await() }

            // The server-authored system notice is visible in the thread.
            val afterSet = service.getConversationDetail(viewerId, detail.conversation.conversationId)
            val systemMessage = afterSet.messages.firstOrNull { it.messageType == "system" }
            assertEquals("Disappearing messages set to 24 hours.", systemMessage?.body)
        }

    @Test
    fun setConversationExpiryOnUnknownConversationIsNotFound() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-0000000000a7")
            val repository = InMemoryMessagingRepository(emptySet())
            val service =
                MessagingService(
                    repository = repository,
                    subscriptionService = subscriptionService(mapOf(viewerId to SubscriptionTier.SELECT)),
                )

            val error =
                assertFailsWith<MessagingException> {
                    service.setConversationExpiry(viewerId, UUID.randomUUID().toString(), 3_600)
                }
            assertEquals("CONVERSATION_NOT_FOUND", error.code)
        }

    @Test
    fun sendMediaMessageAttachesPhotoAndSenderSeesUrl() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
            val targetId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service =
                MessagingService(repository = repository, messageMediaGateway = FakeMessageMediaGateway())
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val assetId = UUID.randomUUID().toString()

            val message =
                service.sendMediaMessage(
                    viewerId,
                    detail.conversation.conversationId,
                    com.pynapli.shared.network.dto
                        .SendMediaMessageRequestDto(mediaAssetId = assetId),
                )

            assertEquals(assetId, message.mediaAssetId)
            assertEquals("image/jpeg", message.mediaContentType)
            assertEquals(true, message.mediaRevealed)
            assertEquals("url://$assetId", message.mediaUrl)
        }

    @Test
    fun sendMediaMessageRejectsModerationFailure() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-0000000000b3")
            val targetId = UUID.fromString("00000000-0000-0000-0000-0000000000b4")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service =
                MessagingService(repository = repository, messageMediaGateway = FakeMessageMediaGateway(rejectAll = true))
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )

            val error =
                assertFailsWith<MessagingException> {
                    service.sendMediaMessage(
                        viewerId,
                        detail.conversation.conversationId,
                        com.pynapli.shared.network.dto
                            .SendMediaMessageRequestDto(mediaAssetId = UUID.randomUUID().toString()),
                    )
                }
            assertEquals("INVALID_MEDIA", error.code)
        }

    @Test
    fun recipientSeesLockedPhotoUntilReveal() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-0000000000b5")
            val targetId = UUID.fromString("00000000-0000-0000-0000-0000000000b6")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service =
                MessagingService(repository = repository, messageMediaGateway = FakeMessageMediaGateway())
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val sent =
                service.sendMediaMessage(
                    viewerId,
                    detail.conversation.conversationId,
                    com.pynapli.shared.network.dto
                        .SendMediaMessageRequestDto(mediaAssetId = UUID.randomUUID().toString()),
                )

            // Recipient sees the photo locked (no URL) until they reveal.
            val targetView = service.getConversationDetail(targetId, detail.conversation.conversationId)
            val targetMessage = targetView.messages.single { it.mediaAssetId != null }
            assertEquals(false, targetMessage.mediaRevealed)
            assertNull(targetMessage.mediaUrl)

            val revealed = service.revealMessageMedia(targetId, sent.messageId)
            assertNotNull(revealed.mediaUrl)
            assertEquals(true, revealed.mediaRevealed)
        }

    @Test
    fun paidReaderPublishesReadDeltaButFreeReaderDoesNot() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000065")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000066")
            val paidRepository = InMemoryMessagingRepository(setOf(targetId))
            val paidBroadcaster = MessageBroadcaster()
            val paidService =
                MessagingService(
                    repository = paidRepository,
                    messageBroadcaster = paidBroadcaster,
                    subscriptionService =
                        subscriptionService(
                            mapOf(viewerId to SubscriptionTier.SOCIAL, targetId to SubscriptionTier.SOCIAL),
                        ),
                )
            val paidDetail =
                paidService.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            paidService.sendMessage(viewerId, paidDetail.conversation.conversationId, SendMessageRequestDto("Paid"))
            val paidDeltaJob = async { paidBroadcaster.deltas.first { it.type == MessageDeltaType.MESSAGE_READ } }
            delay(10)

            paidService.markConversationRead(targetId, paidDetail.conversation.conversationId)

            val paidDelta = withTimeout(1_000) { paidDeltaJob.await() }
            assertEquals(MessageDeltaType.MESSAGE_READ, paidDelta.type)

            val freeRepository = InMemoryMessagingRepository(setOf(targetId))
            val freeBroadcaster = MessageBroadcaster()
            val freeService =
                MessagingService(
                    repository = freeRepository,
                    messageBroadcaster = freeBroadcaster,
                    subscriptionService = subscriptionService(mapOf(viewerId to SubscriptionTier.SOCIAL)),
                )
            val freeDetail =
                freeService.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            freeService.sendMessage(viewerId, freeDetail.conversation.conversationId, SendMessageRequestDto("Free"))
            val freeDeltaJob = async { freeBroadcaster.deltas.first { it.type == MessageDeltaType.MESSAGE_READ } }
            delay(10)

            freeService.markConversationRead(targetId, freeDetail.conversation.conversationId)

            assertNull(withTimeoutOrNull(150) { freeDeltaJob.await() })
            freeDeltaJob.cancel()
        }

    @Test
    fun selectTypingIndicatorPublishesOnlyToSelectRecipients() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000067")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000068")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val broadcaster = MessageBroadcaster()
            val service =
                MessagingService(
                    repository = repository,
                    messageBroadcaster = broadcaster,
                    subscriptionService =
                        subscriptionService(
                            mapOf(viewerId to SubscriptionTier.SELECT, targetId to SubscriptionTier.SELECT),
                        ),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val deltaJob = async { broadcaster.deltas.first { it.type == MessageDeltaType.TYPING_STARTED } }
            delay(10)

            service.sendTypingIndicator(
                viewerId,
                detail.conversation.conversationId,
                SendTypingIndicatorRequestDto(isTyping = true),
            )

            val delta = withTimeout(1_000) { deltaJob.await() }
            assertEquals(targetId, delta.recipientUserId)
            assertEquals(MessageDeltaType.TYPING_STARTED, delta.type)
            assertEquals(viewerId, delta.actorUserId)
            assertEquals("Viewer", delta.actorDisplayName)
            assertEquals(true, delta.expiresAt != null)
        }

    @Test
    fun socialTypingIndicatorIsIgnored() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000069")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000070")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val broadcaster = MessageBroadcaster()
            val service =
                MessagingService(
                    repository = repository,
                    messageBroadcaster = broadcaster,
                    subscriptionService =
                        subscriptionService(
                            mapOf(viewerId to SubscriptionTier.SOCIAL, targetId to SubscriptionTier.SELECT),
                        ),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val deltaJob = async { broadcaster.deltas.first { it.type == MessageDeltaType.TYPING_STARTED } }
            delay(10)

            service.sendTypingIndicator(
                viewerId,
                detail.conversation.conversationId,
                SendTypingIndicatorRequestDto(isTyping = true),
            )

            assertNull(withTimeoutOrNull(150) { deltaJob.await() })
            deltaJob.cancel()
        }

    @Test
    fun getConversationDetailPaginatesOlderMessages() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service = MessagingService(repository)
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("First"))
            service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Second"))
            service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Third"))

            val firstPage =
                service.getConversationDetail(
                    userId = viewerId,
                    conversationIdText = detail.conversation.conversationId,
                    limitText = "2",
                )
            val secondPage =
                service.getConversationDetail(
                    userId = viewerId,
                    conversationIdText = detail.conversation.conversationId,
                    beforeCursorText = firstPage.nextCursor,
                    limitText = "2",
                )

            assertEquals(listOf("Second", "Third"), firstPage.messages.map { it.body })
            assertEquals(listOf("First"), secondPage.messages.map { it.body })
            assertEquals(null, secondPage.nextCursor)
        }

    @Test
    fun linkedPartnerInviteRequiresAcceptBeforeSending() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val partnerId = UUID.fromString("00000000-0000-0000-0000-000000000003")
            val repository =
                InMemoryMessagingRepository(
                    allowedTargets = setOf(targetId),
                    linkedPartnerByUser = mapOf(viewerId to partnerId),
                )
            val notifications = RecordingNotificationPublisher()
            val activityRepository = RecordingActivityRepository()
            val service =
                MessagingService(
                    repository = repository,
                    notificationPublisher = notifications,
                    activityService = ActivityService(activityRepository),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )

            val invited = service.inviteLinkedPartnerToConversation(viewerId, detail.conversation.conversationId)
            val invitedPartner = invited.participants.first { it.userId == partnerId.toString() }

            assertEquals("group", invited.kind)
            assertEquals(ConversationParticipantStatus.INVITED, invitedPartner.status)
            assertEquals(NotificationKind.PARTNER_CHAT_INVITE, notifications.events.single().kind)
            assertEquals(partnerId, notifications.events.single().recipientUserId)
            assertEquals(listOf(Triple(viewerId, partnerId, UUID.fromString(detail.conversation.conversationId))), activityRepository.partnerInvites)
            val sendBeforeAcceptError =
                assertFailsWith<MessagingException> {
                    service.sendMessage(partnerId, detail.conversation.conversationId, SendMessageRequestDto("Joining"))
                }
            assertEquals("CONVERSATION_NOT_FOUND", sendBeforeAcceptError.code)

            val partnerAccepted = service.acceptPartnerInvite(partnerId, detail.conversation.conversationId)
            val waitingPartner = partnerAccepted.conversation.participants.first { it.userId == partnerId.toString() }
            assertEquals(ConversationParticipantStatus.INVITED, waitingPartner.status)
            assertEquals("accepted", partnerAccepted.conversation.partnerInvite?.partnerStatus)
            assertEquals("pending", partnerAccepted.conversation.partnerInvite?.thirdPartyStatus)
            val sendBeforeThirdPartyAcceptError =
                assertFailsWith<MessagingException> {
                    service.sendMessage(partnerId, detail.conversation.conversationId, SendMessageRequestDto("Still waiting"))
                }
            assertEquals("CONVERSATION_NOT_FOUND", sendBeforeThirdPartyAcceptError.code)

            val thirdPartyAccepted = service.acceptPartnerInvite(targetId, detail.conversation.conversationId)
            val acceptedPartner = thirdPartyAccepted.conversation.participants.first { it.userId == partnerId.toString() }
            val sent = service.sendMessage(partnerId, detail.conversation.conversationId, SendMessageRequestDto("Joining"))

            assertEquals(ConversationParticipantStatus.ACTIVE, acceptedPartner.status)
            assertEquals(null, thirdPartyAccepted.conversation.partnerInvite)
            assertEquals("Joining", sent.body)
        }

    @Test
    fun decliningPartnerInviteRemovesInviteeThread() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val partnerId = UUID.fromString("00000000-0000-0000-0000-000000000003")
            val repository =
                InMemoryMessagingRepository(
                    allowedTargets = setOf(targetId),
                    linkedPartnerByUser = mapOf(viewerId to partnerId),
                )
            val service = MessagingService(repository)
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            service.inviteLinkedPartnerToConversation(viewerId, detail.conversation.conversationId)

            service.declinePartnerInvite(partnerId, detail.conversation.conversationId)

            assertEquals(emptyList(), service.listConversations(partnerId).items)
        }

    @Test
    fun sendMessageRejectsEmptyBody() =
        runBlocking {
            val service = MessagingService(InMemoryMessagingRepository(emptySet()))

            val error =
                assertFailsWith<MessagingException> {
                    service.sendMessage(
                        UUID.randomUUID(),
                        UUID.randomUUID().toString(),
                        SendMessageRequestDto(body = " "),
                    )
                }

            assertEquals("MESSAGE_BODY_EMPTY", error.code)
        }

    @Test
    fun sendMessageRejectsRateLimitedFirstMessageBeforePersisting() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000021")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000022")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service =
                MessagingService(
                    repository = repository,
                    rateLimitService = denyingRateLimitService(),
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )

            val error =
                assertFailsWith<MessagingException> {
                    service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("First"))
                }

            assertEquals("RATE_LIMITED", error.code)
            assertEquals(emptyList(), repository.messagesSnapshot(detail.conversation.conversationId))
        }

    @Test
    fun sendMessageRejectsRiskRestrictedSenderBeforePersisting() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000027")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000028")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val conversation = repository.getOrCreateDirectConversation(viewerId, targetId, "grid")!!
            val service =
                MessagingService(
                    repository = repository,
                    riskPolicyService = restrictedRiskPolicy(),
                )

            val error =
                assertFailsWith<MessagingException> {
                    service.sendMessage(
                        viewerId,
                        conversation.conversationId.toString(),
                        SendMessageRequestDto("First"),
                    )
                }

            assertEquals("RISK_RESTRICTED", error.code)
            assertEquals(emptyList(), repository.messagesSnapshot(conversation.conversationId.toString()))
        }

    @Test
    fun sendMessageDoesNotRateLimitFollowUpMessages() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000023")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000024")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val conversation = repository.getOrCreateDirectConversation(viewerId, targetId, "grid")!!
            repository.sendMessage(
                userId = viewerId,
                conversationId = conversation.conversationId,
                body = "Existing",
                moderationStatus = MessageModerationStatus.VISIBLE,
                bodyFingerprint = null,
                safetyReason = null,
                messageIdFactory = UUID::randomUUID,
            )
            val service =
                MessagingService(
                    repository = repository,
                    rateLimitService = denyingRateLimitService(),
                )

            val message =
                service.sendMessage(
                    viewerId,
                    conversation.conversationId.toString(),
                    SendMessageRequestDto("Follow up"),
                )

            assertEquals("Follow up", message.body)
            assertEquals(2, repository.messagesSnapshot(conversation.conversationId.toString()).size)
        }

    @Test
    fun deleteOwnMessageReturnsPlaceholderForEveryParticipant() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000041")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000042")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service = MessagingService(repository)
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val sent = service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Mistake"))

            val deleted = service.deleteOwnMessage(viewerId, sent.messageId)
            val viewerMessages = service.getConversationDetail(viewerId, detail.conversation.conversationId).messages
            val targetMessages = service.getConversationDetail(targetId, detail.conversation.conversationId).messages

            assertEquals("", deleted.body)
            assertEquals("deleted", deleted.deliveryStatus)
            assertEquals(true, deleted.deletedAt != null)
            assertEquals(listOf(""), viewerMessages.map { it.body })
            assertEquals(listOf("deleted"), viewerMessages.map { it.deliveryStatus })
            assertEquals(listOf(""), targetMessages.map { it.body })
            assertEquals(listOf("deleted"), targetMessages.map { it.deliveryStatus })
        }

    @Test
    fun deleteOwnMessagePublishesRealtimeDeltaToActiveParticipants() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000053")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000054")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val broadcaster = MessageBroadcaster()
            val service =
                MessagingService(
                    repository = repository,
                    messageBroadcaster = broadcaster,
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val sent = service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Mistake"))
            val deltaJob = async { broadcaster.deltas.take(2).toList() }
            delay(10)

            service.deleteOwnMessage(viewerId, sent.messageId)

            val deltas = withTimeout(1_000) { deltaJob.await() }
            assertEquals(setOf(viewerId, targetId), deltas.map { it.recipientUserId }.toSet())
            assertEquals(listOf(MessageDeltaType.MESSAGE_DELETED, MessageDeltaType.MESSAGE_DELETED), deltas.map { it.type })
            assertEquals(setOf(UUID.fromString(sent.messageId)), deltas.mapNotNull { it.messageId }.toSet())
        }

    @Test
    fun deleteMessageRejectsNonSender() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000043")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000044")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val service = MessagingService(repository)
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )
            val sent = service.sendMessage(viewerId, detail.conversation.conversationId, SendMessageRequestDto("Owned"))

            val error =
                assertFailsWith<MessagingException> {
                    service.deleteOwnMessage(targetId, sent.messageId)
                }

            assertEquals("MESSAGE_NOT_FOUND", error.code)
        }

    @Test
    fun firstMessageWithExternalLinkIsHeldForReviewAndNotNotified() =
        runBlocking {
            val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000025")
            val targetId = UUID.fromString("00000000-0000-0000-0000-000000000026")
            val repository = InMemoryMessagingRepository(setOf(targetId))
            val notifications = RecordingNotificationPublisher()
            val riskSignals = RecordingMessageReviewRiskSignalPublisher()
            val service =
                MessagingService(
                    repository = repository,
                    notificationPublisher = notifications,
                    messageReviewRiskSignalPublisher = riskSignals,
                )
            val detail =
                service.createDirectConversation(
                    viewerId,
                    CreateDirectConversationRequestDto(targetUserId = targetId.toString()),
                )

            val message =
                service.sendMessage(
                    viewerId,
                    detail.conversation.conversationId,
                    SendMessageRequestDto("Find me at https://example.com"),
                )

            assertEquals("held", message.status)
            assertEquals("held_for_review", message.deliveryStatus)
            assertEquals(emptyList(), notifications.events)
            assertEquals(MessageSafetyReason.LINK_FIRST_MESSAGE, repository.reviewQueue.single().reason)
            assertEquals(
                message.messageId,
                repository.reviewQueue
                    .single()
                    .messageId
                    .toString(),
            )
            assertEquals(MessageSafetyReason.LINK_FIRST_MESSAGE, riskSignals.events.single().reason)

            val senderView = service.getConversationDetail(viewerId, detail.conversation.conversationId)
            val recipientView = service.getConversationDetail(targetId, detail.conversation.conversationId)
            assertEquals(1, senderView.messages.size)
            assertEquals("held", senderView.messages.single().status)
            assertEquals(emptyList(), recipientView.messages)
        }

    @Test
    fun repeatedFirstMessageCopyIsHeldForReview() =
        runBlocking {
            val senderId = UUID.fromString("00000000-0000-0000-0000-000000000031")
            val firstTarget = UUID.fromString("00000000-0000-0000-0000-000000000032")
            val secondTarget = UUID.fromString("00000000-0000-0000-0000-000000000033")
            val thirdTarget = UUID.fromString("00000000-0000-0000-0000-000000000034")
            val repository = InMemoryMessagingRepository(setOf(firstTarget, secondTarget, thirdTarget))
            val service = MessagingService(repository, clock = fixedMessageClock())
            val first =
                service.createDirectConversation(
                    senderId,
                    CreateDirectConversationRequestDto(targetUserId = firstTarget.toString()),
                )
            val second =
                service.createDirectConversation(
                    senderId,
                    CreateDirectConversationRequestDto(targetUserId = secondTarget.toString()),
                )
            val third =
                service.createDirectConversation(
                    senderId,
                    CreateDirectConversationRequestDto(targetUserId = thirdTarget.toString()),
                )

            service.sendMessage(senderId, first.conversation.conversationId, SendMessageRequestDto("Hey, loved your profile"))
            service.sendMessage(senderId, second.conversation.conversationId, SendMessageRequestDto("  hey, loved your profile  "))
            val held =
                service.sendMessage(
                    senderId,
                    third.conversation.conversationId,
                    SendMessageRequestDto("Hey, loved your profile"),
                )

            assertEquals("held", held.status)
            assertEquals(MessageSafetyReason.DUPLICATE_FIRST_MESSAGE, repository.reviewQueue.single().reason)
        }
}

private fun restrictedRiskPolicy(tier: String = "quarantine"): RiskPolicyService =
    RiskPolicyService { userId ->
        AccountRiskStateRecord(
            userId = userId,
            score = 70,
            tier = tier,
            lastEventAt = Instant.parse("2026-04-24T18:00:00Z"),
            lastReviewedAt = null,
            updatedAt = Instant.parse("2026-04-24T18:00:00Z"),
        )
    }

private fun fixedMessageClock(): Clock = Clock.fixed(Instant.parse("2026-04-24T18:00:10Z"), ZoneOffset.UTC)

private class InMemoryMessagingRepository(
    private val allowedTargets: Set<UUID>,
    private val linkedPartnerByUser: Map<UUID, UUID> = emptyMap(),
) : MessagingRepository {
    private val conversationsByPair = mutableMapOf<Set<UUID>, ConversationRecord>()
    private val messagesByConversation = mutableMapOf<UUID, MutableList<MessageRecord>>()
    private val lastReadAtByParticipant = mutableMapOf<Pair<UUID, UUID>, Instant>()
    private val messageFingerprints = mutableMapOf<UUID, String?>()
    private val idempotencyKeys = mutableMapOf<UUID, String>()
    private val partnerInvitesByConversation = mutableMapOf<UUID, PartnerChatInviteRecord>()
    private val expiryByParticipant = mutableMapOf<Pair<UUID, UUID>, Int?>()
    private val mediaRevealsByMessage = mutableMapOf<UUID, MutableSet<UUID>>()
    private var nextConversationIndex = 1
    private var nextMessageIndex = 1
    private var nextInviteIndex = 1
    val reviewQueue = mutableListOf<ReviewQueueItem>()

    override suspend fun listConversations(
        userId: UUID,
        limit: Int,
        after: ConversationPageCursor?,
    ): List<ConversationRecord> =
        conversationsByPair.values
            .filter { conversation ->
                conversation.participants.any {
                    it.userId == userId && it.status in visibleParticipantStatuses
                }
            }.map { conversation ->
                val messages =
                    visibleMessagesFor(
                        userId = userId,
                        conversationId = conversation.conversationId,
                    )
                conversation.copy(
                    lastMessage = messages.lastOrNull()?.withDeliveryStatus(userId),
                    unreadCount = unreadCount(userId, conversation.conversationId),
                    updatedAt = messages.lastOrNull()?.createdAt ?: conversation.updatedAt,
                )
            }.sortedByDescending { it.updatedAt }
            .take(limit)

    override suspend fun getOrCreateDirectConversation(
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String,
        conversationIdFactory: () -> UUID,
    ): ConversationRecord? {
        if (targetUserId !in allowedTargets) return null

        val pair = setOf(viewerUserId, targetUserId)
        return conversationsByPair.getOrPut(pair) {
            val conversationId =
                UUID.fromString("10000000-0000-0000-0000-${nextConversationIndex++.toString().padStart(12, '0')}")
            lastReadAtByParticipant[conversationId to viewerUserId] = Instant.parse("2026-04-24T17:59:00Z")
            lastReadAtByParticipant[conversationId to targetUserId] = Instant.parse("2026-04-24T17:59:00Z")
            ConversationRecord(
                conversationId = conversationId,
                kind = "direct",
                origin = origin,
                participants =
                    listOf(
                        ConversationParticipantRecord(viewerUserId, "Viewer"),
                        ConversationParticipantRecord(targetUserId, "Target"),
                    ),
                lastMessage = null,
                updatedAt = Instant.parse("2026-04-24T18:00:00Z"),
            )
        }
    }

    override suspend fun getActiveLinkedPartnerUserId(userId: UUID): UUID? = linkedPartnerByUser[userId]

    override suspend fun inviteLinkedPartner(
        inviterUserId: UUID,
        conversationId: UUID,
        partnerUserId: UUID,
    ): PartnerInviteResult {
        val entry =
            conversationsByPair.entries.firstOrNull { (_, conversation) ->
                conversation.conversationId == conversationId &&
                    conversation.participants.any {
                        it.userId == inviterUserId && it.status == ConversationParticipantStatus.ACTIVE
                    }
            } ?: return PartnerInviteResult.ConversationNotFound

        val currentPartner = entry.value.participants.firstOrNull { it.userId == partnerUserId }
        if (currentPartner?.status == ConversationParticipantStatus.ACTIVE) {
            return PartnerInviteResult.AlreadyInConversation
        }
        if (currentPartner?.status == ConversationParticipantStatus.INVITED) {
            return PartnerInviteResult.AlreadyInvited
        }

        if (conversationId in partnerInvitesByConversation) {
            return PartnerInviteResult.AlreadyInvited
        }

        val invite =
            PartnerChatInviteRecord(
                inviteId = UUID.fromString("30000000-0000-0000-0000-${nextInviteIndex++.toString().padStart(12, '0')}"),
                threadId = conversationId,
                inviterUserId = inviterUserId,
                invitedPartnerUserId = partnerUserId,
                thirdPartyStatus = "pending",
                partnerStatus = "pending",
                createdAt = Instant.parse("2026-04-24T18:00:00Z"),
            )
        partnerInvitesByConversation[conversationId] = invite
        lastReadAtByParticipant[conversationId to partnerUserId] = Instant.parse("2026-04-24T18:00:00Z")
        val updatedParticipants =
            entry.value.participants
                .filterNot { it.userId == partnerUserId }
                .plus(
                    ConversationParticipantRecord(
                        userId = partnerUserId,
                        displayName = "Partner",
                        status = ConversationParticipantStatus.INVITED,
                        invitedByUserId = inviterUserId,
                    ),
                )
        val updated = entry.value.copy(kind = "group", participants = updatedParticipants, partnerInvite = invite)
        conversationsByPair[entry.key] = updated
        return PartnerInviteResult.Invited(updated)
    }

    override suspend fun acceptPartnerInvite(
        userId: UUID,
        conversationId: UUID,
        inviteId: UUID?,
    ): ConversationDetailRecord? {
        val invite = partnerInvitesByConversation[conversationId] ?: return null
        val entry =
            conversationsByPair.entries.firstOrNull { (_, conversation) ->
                conversation.conversationId == conversationId &&
                    conversation.participants.any { it.userId == userId && it.status in visibleParticipantStatuses }
            } ?: return null

        val role =
            when {
                userId == invite.invitedPartnerUserId -> PartnerInviteUserRole.InvitedPartner
                userId != invite.inviterUserId &&
                    entry.value.participants.any {
                        it.userId == userId && it.status == ConversationParticipantStatus.ACTIVE
                    } -> PartnerInviteUserRole.ThirdPartyApprover
                else -> PartnerInviteUserRole.None
            }
        if (role == PartnerInviteUserRole.None) return null

        val updatedInvite =
            invite.copy(
                partnerStatus = if (role == PartnerInviteUserRole.InvitedPartner) "accepted" else invite.partnerStatus,
                thirdPartyStatus = if (role == PartnerInviteUserRole.ThirdPartyApprover) "accepted" else invite.thirdPartyStatus,
            )
        val fullyAccepted = updatedInvite.partnerStatus == "accepted" && updatedInvite.thirdPartyStatus == "accepted"
        if (fullyAccepted) {
            partnerInvitesByConversation.remove(conversationId)
        } else {
            partnerInvitesByConversation[conversationId] = updatedInvite
        }
        val updated =
            entry.value.copy(
                kind = "group",
                participants =
                    entry.value.participants.map { participant ->
                        if (fullyAccepted && participant.userId == invite.invitedPartnerUserId) {
                            participant.copy(status = ConversationParticipantStatus.ACTIVE)
                        } else {
                            participant
                        }
                    },
                partnerInvite = if (fullyAccepted) null else updatedInvite,
            )
        conversationsByPair[entry.key] = updated
        return getConversationDetail(userId, conversationId, limit = 50)
    }

    override suspend fun declinePartnerInvite(
        userId: UUID,
        conversationId: UUID,
        inviteId: UUID?,
    ): Boolean {
        val invite = partnerInvitesByConversation[conversationId] ?: return false
        val entry =
            conversationsByPair.entries.firstOrNull { (_, conversation) ->
                conversation.conversationId == conversationId &&
                    conversation.participants.any { it.userId == userId && it.status in visibleParticipantStatuses }
            } ?: return false
        val canDecline =
            userId == invite.invitedPartnerUserId ||
                (
                    userId != invite.inviterUserId &&
                        entry.value.participants.any {
                            it.userId == userId && it.status == ConversationParticipantStatus.ACTIVE
                        }
                )
        if (!canDecline) return false
        partnerInvitesByConversation.remove(conversationId)
        return updateParticipantStatus(
            userId = invite.invitedPartnerUserId,
            conversationId = conversationId,
            from = ConversationParticipantStatus.INVITED,
            to = ConversationParticipantStatus.DECLINED,
        )
    }

    override suspend fun leaveConversation(
        userId: UUID,
        conversationId: UUID,
    ): Boolean {
        val entry =
            conversationsByPair.entries.firstOrNull { (_, conversation) ->
                conversation.conversationId == conversationId &&
                    conversation.participants.any {
                        it.userId == userId && it.status in visibleParticipantStatuses
                    }
            } ?: return false
        val updated =
            entry.value.copy(
                participants =
                    entry.value.participants.map { participant ->
                        if (participant.userId == userId) participant.copy(status = ConversationParticipantStatus.LEFT) else participant
                    },
            )
        conversationsByPair[entry.key] = updated
        return true
    }

    override suspend fun sendMediaMessage(
        userId: UUID,
        conversationId: UUID,
        mediaAssetId: UUID,
        contentType: String?,
        pixelWidth: Int?,
        pixelHeight: Int?,
        body: String,
        clientIdempotencyKey: String?,
        messageIdFactory: () -> UUID,
    ): MessageRecord? {
        val isParticipant =
            conversationsByPair.values.any {
                it.conversationId == conversationId &&
                    it.participants.any { p -> p.userId == userId && p.status == ConversationParticipantStatus.ACTIVE }
            }
        if (!isParticipant) return null
        val record =
            MessageRecord(
                messageId = messageIdFactory(),
                conversationId = conversationId,
                senderUserId = userId,
                body = body,
                createdAt = Instant.parse("2026-04-24T18:00:08Z"),
                status = "visible",
                deliveryStatus = "sent",
                mediaAssetId = mediaAssetId,
                mediaContentType = contentType,
                mediaWidth = pixelWidth,
                mediaHeight = pixelHeight,
                mediaRevealedByViewer = true,
            )
        messagesByConversation.getOrPut(conversationId) { mutableListOf() }.add(record)
        return record
    }

    override suspend fun recordMediaReveal(
        userId: UUID,
        messageId: UUID,
    ): MessageRecord? {
        val message =
            messagesByConversation.values.flatten().firstOrNull { it.messageId == messageId }
                ?: return null
        if (message.mediaAssetId == null) return null
        mediaRevealsByMessage.getOrPut(messageId) { mutableSetOf() }.add(userId)
        return message.withDeliveryStatus(userId)
    }

    override suspend fun setConversationExpiry(
        userId: UUID,
        conversationId: UUID,
        expirySeconds: Int?,
        systemMessageBody: String,
        messageIdFactory: () -> UUID,
    ): SetConversationExpiryResult? {
        val entry =
            conversationsByPair.entries.firstOrNull { (_, conversation) ->
                conversation.conversationId == conversationId &&
                    conversation.participants.any {
                        it.userId == userId && it.status == ConversationParticipantStatus.ACTIVE
                    }
            } ?: return null
        expiryByParticipant[conversationId to userId] = expirySeconds
        val updatedParticipants =
            entry.value.participants.map { participant ->
                if (participant.userId == userId) {
                    participant.copy(messageExpirySeconds = expirySeconds)
                } else {
                    participant
                }
            }
        val effective =
            updatedParticipants
                .filter { it.status == ConversationParticipantStatus.ACTIVE }
                .mapNotNull { it.messageExpirySeconds }
                .minOrNull()
        val systemMessage =
            MessageRecord(
                messageId = messageIdFactory(),
                conversationId = conversationId,
                senderUserId = userId,
                body = systemMessageBody,
                createdAt = Instant.parse("2026-04-24T18:00:09Z"),
                status = "visible",
                deliveryStatus = "sent",
                messageType = "system",
            )
        messagesByConversation.getOrPut(conversationId) { mutableListOf() }.add(systemMessage)
        val updated =
            entry.value.copy(
                participants = updatedParticipants,
                viewerExpirySeconds = expirySeconds,
                effectiveExpirySeconds = effective,
            )
        conversationsByPair[entry.key] = updated
        return SetConversationExpiryResult(conversation = updated, systemMessage = systemMessage)
    }

    override suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?,
    ): ConversationDetailRecord? {
        val conversation =
            conversationsByPair.values.firstOrNull {
                it.conversationId == conversationId &&
                    it.participants.any { participant ->
                        participant.userId == userId && participant.status in visibleParticipantStatuses
                    }
            } ?: return null

        val messages = messagesByConversation[conversationId].orEmpty().sortedWith(messageSort)
        val beforeIndex =
            before?.let { cursor ->
                messages
                    .indexOfFirst { message ->
                        message.createdAt == cursor.createdAt && message.messageId == cursor.messageId
                    }.takeIf { it >= 0 }
            }
        val eligible = if (beforeIndex == null) messages else messages.take(beforeIndex)
        val pageMessages = eligible.takeLast(limit)
        val nextCursor = if (eligible.size > limit) pageMessages.firstOrNull()?.toCursor() else null
        val visibleMessages = visibleMessagesFor(userId, pageMessages)
        return ConversationDetailRecord(
            conversation =
                conversation.copy(
                    lastMessage = visibleMessagesFor(userId, conversationId).lastOrNull()?.withDeliveryStatus(userId),
                    unreadCount = unreadCount(userId, conversationId),
                ),
            messages = visibleMessages.map { it.withDeliveryStatus(userId) },
            nextCursor = nextCursor,
        )
    }

    override suspend fun markConversationRead(
        userId: UUID,
        conversationId: UUID,
    ): ConversationRecord? {
        val conversation =
            conversationsByPair.values.firstOrNull {
                it.conversationId == conversationId &&
                    it.participants.any { participant ->
                        participant.userId == userId && participant.status in visibleParticipantStatuses
                    }
            } ?: return null

        val lastMessageAt =
            messagesByConversation[conversationId].orEmpty().lastOrNull()?.createdAt
                ?: Instant.parse("2026-04-24T18:00:00Z")
        lastReadAtByParticipant[conversationId to userId] = lastMessageAt
        return conversation.copy(
            lastMessage = visibleMessagesFor(userId, conversationId).lastOrNull()?.withDeliveryStatus(userId),
            unreadCount = 0,
            updatedAt = lastMessageAt,
        )
    }

    override suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        moderationStatus: String,
        bodyFingerprint: String?,
        safetyReason: String?,
        clientIdempotencyKey: String?,
        messageIdFactory: () -> UUID,
    ): MessageRecord? {
        val conversation =
            conversationsByPair.values.firstOrNull {
                it.conversationId == conversationId &&
                    it.participants.any { participant ->
                        participant.userId == userId && participant.status == ConversationParticipantStatus.ACTIVE
                    }
            } ?: return null

        // Idempotent replay: same sender + conversation + key returns the original.
        if (clientIdempotencyKey != null) {
            val existing =
                messagesByConversation[conversationId].orEmpty().firstOrNull {
                    it.senderUserId == userId && idempotencyKeys[it.messageId] == clientIdempotencyKey
                }
            if (existing != null) return existing
        }

        val messageIndex = nextMessageIndex++
        val message =
            MessageRecord(
                messageId = UUID.fromString("20000000-0000-0000-0000-${messageIndex.toString().padStart(12, '0')}"),
                conversationId = conversation.conversationId,
                senderUserId = userId,
                body = body,
                createdAt = Instant.parse("2026-04-24T18:00:00Z").plusSeconds(messageIndex.toLong()),
                status = moderationStatus,
                deliveryStatus =
                    if (moderationStatus == MessageModerationStatus.HELD) {
                        "held_for_review"
                    } else {
                        "sent"
                    },
            )
        messagesByConversation.getOrPut(conversationId) { mutableListOf() }.add(message)
        messageFingerprints[message.messageId] = bodyFingerprint
        if (clientIdempotencyKey != null) idempotencyKeys[message.messageId] = clientIdempotencyKey
        if (moderationStatus == MessageModerationStatus.HELD && safetyReason != null) {
            reviewQueue +=
                ReviewQueueItem(
                    messageId = message.messageId,
                    senderUserId = userId,
                    conversationId = conversationId,
                    reason = safetyReason,
                )
        }
        lastReadAtByParticipant[conversationId to userId] = message.createdAt
        return message
    }

    override suspend fun deleteOwnMessage(
        userId: UUID,
        messageId: UUID,
    ): MessageRecord? {
        val message =
            messagesByConversation.values
                .flatten()
                .firstOrNull { it.messageId == messageId && it.senderUserId == userId && it.deletedAt == null }
                ?: return null
        val conversation =
            conversationsByPair.values.firstOrNull {
                it.conversationId == message.conversationId &&
                    it.participants.any { participant ->
                        participant.userId == userId && participant.status in visibleParticipantStatuses
                    }
            } ?: return null
        val deletedAt = message.createdAt.plusSeconds(1)
        messagesByConversation[message.conversationId] =
            messagesByConversation[message.conversationId]
                .orEmpty()
                .map { existing ->
                    if (existing.messageId == messageId) {
                        existing.copy(body = "", deliveryStatus = "deleted", deletedAt = deletedAt)
                    } else {
                        existing
                    }
                }.toMutableList()
        return messagesByConversation[conversation.conversationId]
            .orEmpty()
            .first { it.messageId == messageId }
    }

    override suspend fun hasSentMessageInConversation(
        userId: UUID,
        conversationId: UUID,
    ): Boolean = messagesByConversation[conversationId].orEmpty().any { message -> message.senderUserId == userId }

    override suspend fun countRecentMessagesByFingerprint(
        userId: UUID,
        bodyFingerprint: String,
        since: Instant,
    ): Int =
        messagesByConversation.values
            .flatten()
            .count { message ->
                message.senderUserId == userId &&
                    message.createdAt >= since &&
                    messageFingerprints[message.messageId] == bodyFingerprint
            }

    fun messagesSnapshot(conversationId: String): List<MessageRecord> = messagesByConversation[UUID.fromString(conversationId)].orEmpty().toList()

    private fun unreadCount(
        userId: UUID,
        conversationId: UUID,
    ): Int {
        val readAt = lastReadAtByParticipant[conversationId to userId] ?: Instant.EPOCH
        return messagesByConversation[conversationId]
            .orEmpty()
            .count { message ->
                message.senderUserId != userId &&
                    message.status == MessageModerationStatus.VISIBLE &&
                    message.deletedAt == null &&
                    message.createdAt > readAt
            }
    }

    private fun MessageRecord.withDeliveryStatus(viewerUserId: UUID): MessageRecord {
        val withMedia =
            if (mediaAssetId != null) {
                copy(mediaRevealedByViewer = senderUserId == viewerUserId || mediaRevealsByMessage[messageId]?.contains(viewerUserId) == true)
            } else {
                this
            }
        return withMedia.deliveryFor(viewerUserId)
    }

    private fun MessageRecord.deliveryFor(viewerUserId: UUID): MessageRecord {
        if (deletedAt != null) return copy(body = "", deliveryStatus = "deleted")
        if (status == MessageModerationStatus.HELD) return copy(deliveryStatus = "held_for_review")
        if (senderUserId != viewerUserId) return copy(deliveryStatus = "received")
        val conversation =
            conversationsByPair.values.firstOrNull { it.conversationId == conversationId }
                ?: return copy(deliveryStatus = "sent")
        val hasUnreadRecipient =
            conversation.participants
                .filter { participant ->
                    participant.userId != senderUserId &&
                        participant.status == ConversationParticipantStatus.ACTIVE
                }.any { participant ->
                    val readAt = lastReadAtByParticipant[conversationId to participant.userId] ?: Instant.EPOCH
                    readAt < createdAt
                }
        return copy(deliveryStatus = if (hasUnreadRecipient) "sent" else "read")
    }

    private fun visibleMessagesFor(
        userId: UUID,
        conversationId: UUID,
    ): List<MessageRecord> =
        visibleMessagesFor(
            userId = userId,
            messages = messagesByConversation[conversationId].orEmpty().sortedWith(messageSort),
        )

    private fun visibleMessagesFor(
        userId: UUID,
        messages: List<MessageRecord>,
    ): List<MessageRecord> =
        messages.filter { message ->
            message.status == MessageModerationStatus.VISIBLE ||
                (message.status == MessageModerationStatus.HELD && message.senderUserId == userId)
        }

    private fun updateParticipantStatus(
        userId: UUID,
        conversationId: UUID,
        from: String,
        to: String,
    ): Boolean {
        val entry =
            conversationsByPair.entries.firstOrNull { (_, conversation) ->
                conversation.conversationId == conversationId &&
                    conversation.participants.any { it.userId == userId && it.status == from }
            } ?: return false
        conversationsByPair[entry.key] =
            entry.value.copy(
                participants =
                    entry.value.participants.map { participant ->
                        if (participant.userId == userId) participant.copy(status = to) else participant
                    },
                partnerInvite = partnerInvitesByConversation[conversationId],
            )
        return true
    }

    private fun MessageRecord.toCursor(): MessagePageCursor =
        MessagePageCursor(
            createdAt = createdAt,
            messageId = messageId,
        )

    private val visibleParticipantStatuses =
        setOf(
            ConversationParticipantStatus.ACTIVE,
            ConversationParticipantStatus.INVITED,
        )

    private val messageSort = compareBy<MessageRecord> { it.createdAt }.thenBy { it.messageId }
}

private enum class PartnerInviteUserRole {
    None,
    InvitedPartner,
    ThirdPartyApprover,
}

private data class ReviewQueueItem(
    val messageId: UUID,
    val senderUserId: UUID,
    val conversationId: UUID,
    val reason: String,
)

private class RecordingMessageReviewRiskSignalPublisher : MessageReviewRiskSignalPublisher {
    val events = mutableListOf<HeldMessageRiskSignal>()

    override suspend fun recordMessageHeld(
        userId: UUID,
        messageId: UUID,
        reason: String,
    ) {
        events += HeldMessageRiskSignal(userId, messageId, reason)
    }
}

private data class HeldMessageRiskSignal(
    val userId: UUID,
    val messageId: UUID,
    val reason: String,
)

private class FakeMessageMediaGateway(
    private val rejectAll: Boolean = false,
) : MessageMediaGateway {
    override suspend fun createUploadSlot(
        ownerUserId: UUID,
        request: com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto,
    ): com.pynapli.shared.network.dto.MessageMediaUploadSlotDto =
        com.pynapli.shared.network.dto.MessageMediaUploadSlotDto(
            mediaAssetId = UUID.randomUUID().toString(),
            uploadUrl = "url://upload",
            uploadMethod = "PUT",
            uploadHeaders = emptyMap(),
            expiresAt = "2026-04-24T18:10:00Z",
        )

    override suspend fun prepareOutboundMedia(
        senderUserId: UUID,
        mediaAssetId: UUID,
    ): PreparedMessageMedia? = if (rejectAll) null else PreparedMessageMedia(contentType = "image/jpeg", pixelWidth = 800, pixelHeight = 600)

    override fun contentUrl(mediaAssetId: UUID): String = "url://$mediaAssetId"
}

private fun subscriptionService(tiersByUserId: Map<UUID, String>): SubscriptionService =
    SubscriptionService(
        StaticSubscriptionRepository(
            own =
                tiersByUserId.mapValues { (userId, tier) ->
                    SubscriptionRecord(
                        subscriptionId = UUID.randomUUID(),
                        userId = userId,
                        tier = tier,
                        status = "active",
                        currentPeriodStart = Instant.parse("2026-05-01T00:00:00Z"),
                        currentPeriodEnd = Instant.parse("2026-06-01T00:00:00Z"),
                    )
                },
        ),
    )

private class RecordingNotificationPublisher : NotificationPublisher {
    val events = mutableListOf<NotificationEventDraft>()

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        events += notification
        return null
    }
}

private class RecordingActivityRepository : ActivityRepository {
    val partnerInvites = mutableListOf<Triple<UUID, UUID, UUID>>()

    override suspend fun recordProfileView(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): Boolean = true

    override suspend fun recordFlirt(
        senderUserId: UUID,
        targetUserId: UUID,
        source: String?,
    ): Boolean = true

    override suspend fun recordAlbumRequest(
        requesterUserId: UUID,
        targetUserId: UUID,
    ): Boolean = true

    override suspend fun recordSwipeInterest(
        actorUserId: UUID,
        targetUserId: UUID,
        eventType: String,
        metadataJson: String,
    ): Boolean = true

    override suspend fun recordMatch(
        recipientUserId: UUID,
        actorUserId: UUID,
        matchId: UUID,
    ): Boolean = true

    override suspend fun recordPartnerChatInvite(
        inviterUserId: UUID,
        inviteeUserId: UUID,
        conversationId: UUID,
    ): Boolean {
        partnerInvites += Triple(inviterUserId, inviteeUserId, conversationId)
        return true
    }

    override suspend fun getAlbumRequestEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): AlbumRequestEventRecord? = null

    override suspend fun listActivity(query: ActivityFeedQuery): List<ActivityEventRecord> = emptyList()

    override suspend fun getActivitySummary(recipientUserId: UUID): ActivitySummaryRecord =
        ActivitySummaryRecord(
            profileViewCount = 0,
            flirtCount = 0,
            likeCount = 0,
            albumRequestCount = 0,
            partnerInviteCount = 0,
            latestBadgeOccurredAt = null,
        )

    override suspend fun markActivityEventsSeen(
        recipientUserId: UUID,
        eventIds: Set<UUID>,
    ): Int = 0

    override suspend fun dismissActivityEvent(
        recipientUserId: UUID,
        eventId: UUID,
    ): Boolean = false
}
