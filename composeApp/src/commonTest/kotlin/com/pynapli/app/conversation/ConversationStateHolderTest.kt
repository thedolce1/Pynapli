package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.PartnerChatInviteDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationStateHolderTest {
    @Test
    fun failedSendRetryReusesIdempotencyKeyThenFreshKeyAfterSuccess() =
        runTest {
            val repository = FakeConversationRepository()
            // First send fails, retry succeeds; a later fresh send succeeds too.
            repository.sendResults.addLast(AppResult.Failure(AppError("RATE_LIMITED", "Slow down.")))
            repository.sendResults.addLast(AppResult.Success(message(senderUserId = "viewer", messageId = "m-1")))
            repository.sendResults.addLast(AppResult.Success(message(senderUserId = "viewer", messageId = "m-2")))

            val keys = ArrayDeque(listOf("key-A", "key-B"))
            val stateHolder =
                ConversationStateHolder(
                    repository,
                    idempotencyKeyFactory = { keys.removeFirst() },
                )
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.updateDraft("Drinks?")
            stateHolder.sendDraft() // fails — key minted, draft preserved
            stateHolder.sendDraft() // retry — must reuse the same key
            stateHolder.updateDraft("A new message")
            stateHolder.sendDraft() // fresh send — new key

            assertEquals(listOf<String?>("key-A", "key-A", "key-B"), repository.sendIdempotencyKeys.toList())
        }

    @Test
    fun editingTheDraftAfterAFailedSendSendsTheEditNotTheOldText() =
        runTest {
            val repository = FakeConversationRepository()
            repository.sendResults.addLast(AppResult.Failure(AppError("NETWORK_ERROR", "Check your connection.")))
            repository.sendResults.addLast(AppResult.Success(message(senderUserId = "viewer", messageId = "m-1")))
            val keys = ArrayDeque(listOf("key-A", "key-B"))
            val stateHolder = ConversationStateHolder(repository, idempotencyKeyFactory = { keys.removeFirst() })
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.updateDraft("Drinks Thursday?")
            stateHolder.sendDraft() // fails; it may still have reached the server under key-A
            stateHolder.updateDraft("Drinks Friday?")
            stateHolder.sendDraft() // the edited text is a new message, so it needs a new key

            assertEquals(listOf<String?>("key-A", "key-B"), repository.sendIdempotencyKeys.toList())
        }

    @Test
    fun deleteOwnMessageReplacesBodyWithPlaceholderState() =
        runTest {
            val repository = FakeConversationRepository()
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.deleteMessage("message-1")

            val message =
                stateHolder.state.value.detail
                    ?.messages
                    ?.single()
            assertEquals("message-1", repository.deletedMessageId)
            assertEquals("", message?.body)
            assertEquals("deleted", message?.deliveryStatus)
            assertEquals("2026-05-22T18:01:00Z", message?.deletedAt)
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun reportingTheirMessageSendsItOnceAndSaysWhatHappens() =
        runTest {
            val repository = FakeConversationRepository(initialMessage = message(senderUserId = "target"))
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.reportMessage("message-1", "harassment_or_threats")
            stateHolder.reportMessage("message-1", "harassment_or_threats")

            assertEquals(listOf(Triple("target", "message-1", "harassment_or_threats")), repository.reportedMessages)
            assertEquals(setOf("message-1"), stateHolder.state.value.reportedMessageIds)
            assertEquals("Thanks, we'll review that message. You can also block them from their profile.", stateHolder.state.value.reportFeedback)
        }

    @Test
    fun yourOwnMessageCannotBeReported() =
        runTest {
            val repository = FakeConversationRepository()
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.reportMessage("message-1", "other")

            assertEquals(emptyList(), repository.reportedMessages)
        }

    @Test
    fun deleteMessageIgnoresMessageFromOtherParticipant() =
        runTest {
            val repository =
                FakeConversationRepository(
                    initialMessage = message(senderUserId = "target"),
                )
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.deleteMessage("message-1")

            assertNull(repository.deletedMessageId)
            assertEquals(
                "Original",
                stateHolder.state.value.detail
                    ?.messages
                    ?.single()
                    ?.body,
            )
        }

    @Test
    fun setExpiryUpdatesConversationAndIsANoOpForTheSameValue() =
        runTest {
            val repository = FakeConversationRepository()
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.setExpiry(86_400)
            assertEquals(listOf<Int?>(86_400), repository.expiryCalls)
            assertEquals(86_400, stateHolder.state.value.viewerExpirySeconds)
            assertEquals(86_400, stateHolder.state.value.effectiveExpirySeconds)
            assertFalse(stateHolder.state.value.isSettingExpiry)

            // Re-selecting the current value does not call the server again.
            stateHolder.setExpiry(86_400)
            assertEquals(listOf<Int?>(86_400), repository.expiryCalls)
        }

    @Test
    fun disappearingMessagesWithoutAPlanOfferThePlansInsteadOfAnError() =
        runTest {
            val repository =
                FakeConversationRepository().apply {
                    expiryResult = AppResult.Failure(AppError("ENTITLEMENT_REQUIRED", "Upgrade is required for this feature."))
                }
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.setExpiry(3_600)

            assertTrue(stateHolder.state.value.expiryNeedsUpgrade)
            assertNull(stateHolder.state.value.errorMessage)
            assertFalse(stateHolder.state.value.isSettingExpiry)
        }

    @Test
    fun otherExpiryFailuresStillShowTheError() =
        runTest {
            val repository =
                FakeConversationRepository().apply {
                    expiryResult = AppResult.Failure(AppError("NETWORK_ERROR", "Check your connection."))
                }
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.setExpiry(3_600)

            assertFalse(stateHolder.state.value.expiryNeedsUpgrade)
            assertEquals("Check your connection.", stateHolder.state.value.errorMessage)
        }

    @Test
    fun sendPhotoAppendsMediaMessageToThread() =
        runTest {
            val repository = FakeConversationRepository()
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.sendPhoto(
                com.pynapli.app.media.PickedMediaForUpload(
                    contentType = "image/jpeg",
                    byteSize = 4,
                    pixelWidth = 800,
                    pixelHeight = 600,
                    bytes = byteArrayOf(1, 2, 3, 4),
                ),
            )

            assertEquals(listOf("image/jpeg"), repository.sendPhotoCalls)
            val photo =
                stateHolder.state.value.detail
                    ?.messages
                    ?.last()
            assertEquals("asset-1", photo?.mediaAssetId)
            assertEquals("https://media.example/photo-1", photo?.mediaUrl)
            assertFalse(stateHolder.state.value.isSendingPhoto)
        }

    @Test
    fun revealMediaSwapsLockedPlaceholderForRevealedMessage() =
        runTest {
            val locked =
                message(senderUserId = "target", body = "").copy(
                    messageId = "message-1",
                    mediaAssetId = "asset-1",
                    mediaUrl = null,
                    mediaRevealed = false,
                )
            val repository = FakeConversationRepository(initialMessage = locked)
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.revealMedia("message-1")

            assertEquals(listOf("message-1"), repository.revealCalls)
            val revealed =
                stateHolder.state.value.detail
                    ?.messages
                    ?.single { it.messageId == "message-1" }
            assertEquals("https://media.example/message-1", revealed?.mediaUrl)
            assertEquals(true, revealed?.mediaRevealed)
            assertEquals(emptySet(), stateHolder.state.value.revealingMessageIds)
        }

    @Test
    fun refreshOpenConversationIfConversationIdOnlyRefreshesMatchingThread() =
        runTest {
            val repository = FakeConversationRepository()
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")
            repository.detailMessage = message(senderUserId = "target", body = "Realtime update")

            stateHolder.refreshOpenConversationIfConversationId("other-conversation")
            assertEquals(
                "Original",
                stateHolder.state.value.detail
                    ?.messages
                    ?.single()
                    ?.body,
            )

            stateHolder.refreshOpenConversationIfConversationId("conversation-1")
            assertEquals(
                "Realtime update",
                stateHolder.state.value.detail
                    ?.messages
                    ?.last()
                    ?.body,
            )
        }

    @Test
    fun acceptPartnerInviteKeepsJoinedFeedbackThroughSilentRealtimeRefresh() =
        runTest {
            val invited = partnerInviteConversation(viewerStatus = ConversationParticipantStatus.INVITED)
            val joined = partnerInviteConversation(viewerStatus = ConversationParticipantStatus.ACTIVE, partnerInvite = null)
            val repository =
                FakeConversationRepository().apply {
                    conversation = invited
                    detailConversation = invited
                    acceptResult =
                        AppResult.Success(
                            ConversationDetailDto(conversation = joined, messages = listOf(detailMessage)),
                        )
                }
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(invited, viewerUserId = "viewer")

            stateHolder.acceptPartnerInvite()
            assertEquals("Joined chat", stateHolder.state.value.partnerInviteFeedback)

            repository.detailConversation = joined
            stateHolder.refreshOpenConversationIfConversationId("conversation-1")

            assertEquals("Joined chat", stateHolder.state.value.partnerInviteFeedback)
        }

    @Test
    fun typingStartedAddsAndExpiresParticipantForOpenConversation() =
        runTest {
            val repository = FakeConversationRepository()
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.applyTypingStarted(
                conversationId = "conversation-1",
                actorUserId = "target",
                actorDisplayName = "Maya",
                expiresAt = "2026-05-22T18:00:06Z",
            )

            assertEquals(
                listOf("Maya"),
                stateHolder.state.value.typingParticipants
                    .map { it.displayName },
            )

            stateHolder.clearExpiredTypingIndicators(nowMillis = Long.MAX_VALUE)

            assertEquals(emptyList(), stateHolder.state.value.typingParticipants)
        }

    @Test
    fun typingStartedIgnoresOwnAndOtherConversationEvents() =
        runTest {
            val repository = FakeConversationRepository()
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.applyTypingStarted(
                conversationId = "other-conversation",
                actorUserId = "target",
                actorDisplayName = "Maya",
                expiresAt = null,
            )
            stateHolder.applyTypingStarted(
                conversationId = "conversation-1",
                actorUserId = "viewer",
                actorDisplayName = "You",
                expiresAt = null,
            )

            assertEquals(emptyList(), stateHolder.state.value.typingParticipants)
        }

    @Test
    fun typingSignalIsThrottledAndStopIsSentOnce() =
        runTest {
            val repository = FakeConversationRepository()
            val stateHolder = ConversationStateHolder(repository)
            stateHolder.openConversation(repository.conversation, viewerUserId = "viewer")

            stateHolder.sendTypingSignal(isTyping = true)
            stateHolder.sendTypingSignal(isTyping = true)
            stateHolder.sendTypingStopped("conversation-1")
            stateHolder.sendTypingStopped("conversation-1")

            assertEquals(
                listOf("conversation-1" to true, "conversation-1" to false),
                repository.typingCalls,
            )
        }
}

private class FakeConversationRepository(
    private val initialMessage: MessageDto = message(senderUserId = "viewer"),
) : ConversationRepository {
    var conversation =
        ConversationDto(
            conversationId = "conversation-1",
            kind = "direct",
            origin = "grid",
            participants =
                listOf(
                    ConversationParticipantDto("viewer", "You"),
                    ConversationParticipantDto("target", "Maya"),
                ),
            lastMessage = initialMessage,
            unreadCount = 0,
            updatedAt = "2026-05-22T18:00:00Z",
        )
    var deletedMessageId: String? = null
    val reportedMessages = mutableListOf<Triple<String, String, String>>()

    override suspend fun reportMessage(
        reportedUserId: String,
        messageId: String,
        category: String,
    ): AppResult<Unit> {
        reportedMessages += Triple(reportedUserId, messageId, category)
        return AppResult.Success(Unit)
    }

    var detailMessage: MessageDto = initialMessage
    var detailConversation: ConversationDto = conversation
    var acceptResult: AppResult<ConversationDetailDto> = AppResult.Failure(AppError("UNUSED", "Unused."))
    val typingCalls = mutableListOf<Pair<String, Boolean>>()

    // Records the idempotency key passed to each sendMessage call (in order). When [sendResults] is
    // non-empty, successive calls consume it; otherwise sendMessage returns the default failure.
    val sendIdempotencyKeys = mutableListOf<String?>()
    val sendResults = ArrayDeque<AppResult<MessageDto>>()

    override suspend fun createDirectConversation(
        targetUserId: String,
        origin: String,
    ): AppResult<ConversationDetailDto> = AppResult.Failure(AppError("UNUSED", "Unused."))

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?,
    ): AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(
                conversation = detailConversation.copy(lastMessage = detailMessage),
                messages = listOf(detailMessage),
            ),
        )

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> = AppResult.Success(detailConversation.copy(unreadCount = 0))

    override suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientIdempotencyKey: String?,
    ): AppResult<MessageDto> {
        sendIdempotencyKeys += clientIdempotencyKey
        return if (sendResults.isNotEmpty()) sendResults.removeFirst() else AppResult.Failure(AppError("UNUSED", "Unused."))
    }

    override suspend fun sendTypingIndicator(
        conversationId: String,
        isTyping: Boolean,
    ): AppResult<Unit> {
        typingCalls += conversationId to isTyping
        return AppResult.Success(Unit)
    }

    val expiryCalls = mutableListOf<Int?>()
    var expiryResult: AppResult<ConversationDto>? = null

    override suspend fun setConversationExpiry(
        conversationId: String,
        expirySeconds: Int?,
    ): AppResult<ConversationDto> {
        expiryCalls += expirySeconds
        val updated = detailConversation.copy(messageExpirySeconds = expirySeconds, effectiveMessageExpirySeconds = expirySeconds)
        detailConversation = updated
        return expiryResult ?: AppResult.Success(updated)
    }

    val sendPhotoCalls = mutableListOf<String>()
    val revealCalls = mutableListOf<String>()

    override suspend fun sendPhoto(
        conversationId: String,
        contentType: String,
        byteSize: Long,
        pixelWidth: Int?,
        pixelHeight: Int?,
        bytes: ByteArray,
    ): AppResult<MessageDto> {
        sendPhotoCalls += contentType
        return AppResult.Success(
            message(senderUserId = "viewer", body = "").copy(
                messageId = "photo-1",
                mediaAssetId = "asset-1",
                mediaUrl = "https://media.example/photo-1",
                mediaRevealed = true,
            ),
        )
    }

    override suspend fun revealMedia(messageId: String): AppResult<MessageDto> {
        revealCalls += messageId
        return AppResult.Success(
            message(senderUserId = "target", body = "").copy(
                messageId = messageId,
                mediaAssetId = "asset-1",
                mediaUrl = "https://media.example/$messageId",
                mediaRevealed = true,
            ),
        )
    }

    override suspend fun deleteMessage(messageId: String): AppResult<MessageDto> {
        deletedMessageId = messageId
        return AppResult.Success(
            initialMessage.copy(
                body = "",
                deliveryStatus = "deleted",
                deletedAt = "2026-05-22T18:01:00Z",
            ),
        )
    }

    override suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto> = AppResult.Failure(AppError("UNUSED", "Unused."))

    override suspend fun acceptPartnerInvite(conversationId: String, inviteId: String?): AppResult<ConversationDetailDto> {
        if (acceptResult is AppResult.Success) {
            detailConversation = (acceptResult as AppResult.Success<ConversationDetailDto>).value.conversation
        }
        return acceptResult
    }

    override suspend fun declinePartnerInvite(conversationId: String, inviteId: String?): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED))

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.LEFT))
}

private fun message(
    senderUserId: String,
    body: String = "Original",
    messageId: String = "message-1",
): MessageDto =
    MessageDto(
        messageId = messageId,
        conversationId = "conversation-1",
        senderUserId = senderUserId,
        body = body,
        createdAt = "2026-05-22T18:00:00Z",
        status = "visible",
        deliveryStatus = "sent",
    )

private fun partnerInviteConversation(
    viewerStatus: String,
    partnerInvite: PartnerChatInviteDto? =
        PartnerChatInviteDto(
            inviteId = "invite-1",
            inviterUserId = "maya",
            invitedPartnerUserId = "viewer",
            thirdPartyStatus = PartnerChatInviteStatus.ACCEPTED,
            partnerStatus = PartnerChatInviteStatus.PENDING,
            createdAt = "2026-05-22T18:00:00Z",
        ),
): ConversationDto =
    ConversationDto(
        conversationId = "conversation-1",
        kind = "group",
        origin = "grid",
        participants =
            listOf(
                ConversationParticipantDto(
                    userId = "viewer",
                    displayName = "You",
                    status = viewerStatus,
                    invitedByUserId = "maya",
                ),
                ConversationParticipantDto(
                    userId = "maya",
                    displayName = "Maya",
                    status = ConversationParticipantStatus.ACTIVE,
                ),
            ),
        unreadCount = 0,
        updatedAt = "2026-05-22T18:00:00Z",
        partnerInvite = partnerInvite,
    )
