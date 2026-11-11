package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotificationServiceTest {
    @Test
    fun getPreferencesReturnsDefaults() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = NotificationService(InMemoryNotificationRepository(userId))

            val preferences = service.getPreferences(userId)

            assertEquals("discreet", preferences.detailLevel)
            assertEquals(true, preferences.messagesEnabled)
            assertEquals(true, preferences.matchesEnabled)
            assertEquals(true, preferences.activityEnabled)
            assertEquals(false, preferences.quietHoursEnabled)
            assertEquals(22 * 60, preferences.quietHoursStartMinute)
            assertEquals(8 * 60, preferences.quietHoursEndMinute)
            assertEquals("UTC", preferences.quietHoursTimezone)
        }

    @Test
    fun updatePreferencesStoresServerOwnedNotificationToggles() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = NotificationService(InMemoryNotificationRepository(userId))

            val preferences =
                service.updatePreferences(
                    userId,
                    UpdateNotificationPreferencesRequestDto(
                        detailLevel = " standard ",
                        messagesEnabled = false,
                        matchesEnabled = true,
                        activityEnabled = false,
                        flirtsEnabled = false,
                        albumRequestsEnabled = false,
                        partnerInvitesEnabled = true,
                        systemSafetyEnabled = true,
                        quietHoursEnabled = true,
                        quietHoursStartMinute = 23 * 60,
                        quietHoursEndMinute = 7 * 60,
                        quietHoursTimezone = " America/Toronto ",
                    ),
                )

            assertEquals("standard", preferences.detailLevel)
            assertEquals(false, preferences.messagesEnabled)
            assertEquals(true, preferences.matchesEnabled)
            assertEquals(false, preferences.activityEnabled)
            assertEquals(false, preferences.flirtsEnabled)
            assertEquals(false, preferences.albumRequestsEnabled)
            assertEquals(true, preferences.partnerInvitesEnabled)
            assertEquals(true, preferences.systemSafetyEnabled)
            assertEquals(true, preferences.quietHoursEnabled)
            assertEquals(23 * 60, preferences.quietHoursStartMinute)
            assertEquals(7 * 60, preferences.quietHoursEndMinute)
            assertEquals("America/Toronto", preferences.quietHoursTimezone)
        }

    @Test
    fun updatePreferencesRejectsUnsupportedDetailLevel() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = NotificationService(InMemoryNotificationRepository(userId))

            val error =
                assertFailsWith<NotificationException> {
                    service.updatePreferences(
                        userId,
                        UpdateNotificationPreferencesRequestDto(detailLevel = "verbose"),
                    )
                }

            assertEquals("INVALID_NOTIFICATION_DETAIL_LEVEL", error.code)
        }

    @Test
    fun updatePreferencesRejectsInvalidQuietHoursValues() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = NotificationService(InMemoryNotificationRepository(userId))

            val windowError =
                assertFailsWith<NotificationException> {
                    service.updatePreferences(
                        userId,
                        UpdateNotificationPreferencesRequestDto(
                            quietHoursEnabled = true,
                            quietHoursStartMinute = 8 * 60,
                            quietHoursEndMinute = 8 * 60,
                        ),
                    )
                }
            val timezoneError =
                assertFailsWith<NotificationException> {
                    service.updatePreferences(
                        userId,
                        UpdateNotificationPreferencesRequestDto(quietHoursTimezone = "Moon/Base"),
                    )
                }

            assertEquals("INVALID_QUIET_HOURS_WINDOW", windowError.code)
            assertEquals("INVALID_QUIET_HOURS_TIMEZONE", timezoneError.code)
        }

    @Test
    fun registerPushDeviceNormalizesAndStoresRegistration() =
        runBlocking {
            val userId = UUID.randomUUID()
            val repository = InMemoryNotificationRepository(userId)
            val service = NotificationService(repository)

            val response =
                service.registerPushDevice(
                    userId,
                    RegisterPushDeviceRequestDto(
                        platform = " Android ",
                        deviceToken = " 1234567890abcdef ",
                        appVersion = "0.1.0",
                        locale = "en-CA",
                        timezone = "America/Toronto",
                        notificationsEnabled = true,
                    ),
                )

            assertEquals("android", response.device.platform)
            assertEquals("android", repository.registration?.platform)
            assertEquals("1234567890abcdef", repository.registration?.deviceToken)
            assertEquals("America/Toronto", repository.registration?.timezone)
        }

    @Test
    fun registerPushDeviceRejectsInvalidPlatformAndShortToken() =
        runBlocking {
            val userId = UUID.randomUUID()
            val service = NotificationService(InMemoryNotificationRepository(userId))

            val platformError =
                assertFailsWith<NotificationException> {
                    service.registerPushDevice(
                        userId,
                        RegisterPushDeviceRequestDto(platform = "web", deviceToken = "1234567890abcdef"),
                    )
                }
            val tokenError =
                assertFailsWith<NotificationException> {
                    service.registerPushDevice(
                        userId,
                        RegisterPushDeviceRequestDto(platform = "ios", deviceToken = "short"),
                    )
                }

            assertEquals("INVALID_PUSH_PLATFORM", platformError.code)
            assertEquals("INVALID_PUSH_TOKEN", tokenError.code)
        }

    @Test
    fun enqueueWritesDiscreetOutboxWhenPreferenceAndDeviceAllowIt() =
        runBlocking {
            val userId = UUID.randomUUID()
            val actorId = UUID.randomUUID()
            val conversationId = UUID.randomUUID()
            val repository = InMemoryNotificationRepository(userId)
            val service = NotificationService(repository)

            val outbox =
                service.enqueue(
                    NotificationEventDraft(
                        recipientUserId = userId,
                        actorUserId = actorId,
                        kind = NotificationKind.MESSAGE,
                        subjectId = conversationId,
                        idempotencyKey = "message:test:$userId",
                        payload = mapOf("conversationId" to conversationId.toString()),
                    ),
                )

            assertNotNull(outbox)
            assertEquals(NotificationKind.MESSAGE, outbox.kind)
            assertEquals("Pynapli", repository.outbox.single().title)
            assertEquals("You have a new update.", repository.outbox.single().body)
            assertEquals("message:$userId:conversation:$conversationId", repository.outbox.single().throttleKey)
            assertEquals(
                true,
                repository.outbox
                    .single()
                    .payloadJson
                    .contains("\"detailLevel\":\"discreet\""),
            )
        }

    @Test
    fun aFailedOutboxWriteIsDroppedInsteadOfFailingTheCaller() =
        runBlocking {
            val userId = UUID.randomUUID()
            val repository = InMemoryNotificationRepository(userId).apply { failOutboxWrites = true }
            val service = NotificationService(repository)

            val outbox =
                service.enqueue(
                    NotificationEventDraft(
                        recipientUserId = userId,
                        actorUserId = UUID.randomUUID(),
                        kind = NotificationKind.MESSAGE,
                        subjectId = UUID.randomUUID(),
                        idempotencyKey = "message:fail:$userId",
                    ),
                )

            assertEquals(null, outbox)
        }

    @Test
    fun enqueueRateLimitsMessageBurstsByConversation() =
        runBlocking {
            val userId = UUID.randomUUID()
            val actorId = UUID.randomUUID()
            val conversationId = UUID.randomUUID()
            val repository = InMemoryNotificationRepository(userId)
            repository.recentNotifications +=
                RecentNotification(
                    recipientUserId = userId,
                    kind = NotificationKind.MESSAGE,
                    throttleKey = "message:$userId:conversation:$conversationId",
                )
            val service = NotificationService(repository, clock = fixedClock)

            val outbox =
                service.enqueue(
                    NotificationEventDraft(
                        recipientUserId = userId,
                        actorUserId = actorId,
                        kind = NotificationKind.MESSAGE,
                        subjectId = conversationId,
                        idempotencyKey = "message:burst:$userId",
                        payload = mapOf("conversationId" to conversationId.toString()),
                    ),
                )

            assertNull(outbox)
            assertEquals(emptyList(), repository.outbox)
            val check = repository.recentChecks.single()
            assertEquals(NotificationKind.MESSAGE, check.kind)
            assertEquals("message:$userId:conversation:$conversationId", check.throttleKey)
            assertEquals(Instant.parse("2026-04-30T23:58:30Z"), check.since)
        }

    @Test
    fun enqueueRateLimitsRepeatedSocialSignalsFromSameActor() =
        runBlocking {
            val userId = UUID.randomUUID()
            val actorId = UUID.randomUUID()
            val repository = InMemoryNotificationRepository(userId)
            repository.recentNotifications +=
                RecentNotification(
                    recipientUserId = userId,
                    kind = NotificationKind.FLIRT,
                    throttleKey = "flirt:$userId:actor:$actorId",
                )
            val service = NotificationService(repository, clock = fixedClock)

            val outbox =
                service.enqueue(
                    NotificationEventDraft(
                        recipientUserId = userId,
                        actorUserId = actorId,
                        kind = NotificationKind.FLIRT,
                        subjectId = actorId,
                        idempotencyKey = "flirt:burst:$actorId:$userId",
                        payload = mapOf("actorUserId" to actorId.toString()),
                    ),
                )

            assertNull(outbox)
            assertEquals(emptyList(), repository.outbox)
            val check = repository.recentChecks.single()
            assertEquals(NotificationKind.FLIRT, check.kind)
            assertEquals("flirt:$userId:actor:$actorId", check.throttleKey)
            assertEquals(Instant.parse("2026-04-30T18:00:00Z"), check.since)
        }

    @Test
    fun enqueueAggregatesProfileViewsByRecipientWindow() =
        runBlocking {
            val userId = UUID.randomUUID()
            val viewerId = UUID.randomUUID()
            val repository = InMemoryNotificationRepository(userId)
            val service = NotificationService(repository, clock = fixedClock)

            val outbox =
                service.enqueue(
                    NotificationEventDraft(
                        recipientUserId = userId,
                        actorUserId = viewerId,
                        kind = NotificationKind.PROFILE_VIEW,
                        subjectId = userId,
                        idempotencyKey = "profile_view:$userId:$viewerId",
                        payload = mapOf("viewerUserId" to viewerId.toString()),
                    ),
                )

            assertNotNull(outbox)
            val insert = repository.outbox.single()
            assertEquals(NotificationKind.PROFILE_VIEW, insert.kind)
            assertEquals("profile_view:$userId", insert.throttleKey)
            assertEquals(
                "profile_view:$userId:${fixedClock.instant().epochSecond / (6L * 60L * 60L)}",
                insert.idempotencyKey,
            )
        }

    @Test
    fun enqueueRateLimitsProfileViewsByRecipient() =
        runBlocking {
            val userId = UUID.randomUUID()
            val viewerId = UUID.randomUUID()
            val repository = InMemoryNotificationRepository(userId)
            repository.recentNotifications +=
                RecentNotification(
                    recipientUserId = userId,
                    kind = NotificationKind.PROFILE_VIEW,
                    throttleKey = "profile_view:$userId",
                )
            val service = NotificationService(repository, clock = fixedClock)

            val outbox =
                service.enqueue(
                    NotificationEventDraft(
                        recipientUserId = userId,
                        actorUserId = viewerId,
                        kind = NotificationKind.PROFILE_VIEW,
                        subjectId = userId,
                        idempotencyKey = "profile_view:burst:$userId:$viewerId",
                        payload = mapOf("viewerUserId" to viewerId.toString()),
                    ),
                )

            assertNull(outbox)
            assertEquals(emptyList(), repository.outbox)
            val check = repository.recentChecks.single()
            assertEquals(NotificationKind.PROFILE_VIEW, check.kind)
            assertEquals("profile_view:$userId", check.throttleKey)
            assertEquals(Instant.parse("2026-04-30T18:00:00Z"), check.since)
        }

    @Test
    fun enqueueSkipsWhenSpecificPreferenceOrDeviceGateBlocksIt() =
        runBlocking {
            val userId = UUID.randomUUID()
            val repository = InMemoryNotificationRepository(userId)
            val service = NotificationService(repository)

            repository.preferences = repository.preferences?.copy(messagesEnabled = false)
            val disabledByPreference =
                service.enqueue(
                    NotificationEventDraft(
                        recipientUserId = userId,
                        actorUserId = UUID.randomUUID(),
                        kind = NotificationKind.MESSAGE,
                        subjectId = UUID.randomUUID(),
                        idempotencyKey = "message:disabled:$userId",
                    ),
                )

            repository.preferences = repository.preferences?.copy(messagesEnabled = true)
            repository.activeDeviceCount = 0
            val disabledByDevice =
                service.enqueue(
                    NotificationEventDraft(
                        recipientUserId = userId,
                        actorUserId = UUID.randomUUID(),
                        kind = NotificationKind.MESSAGE,
                        subjectId = UUID.randomUUID(),
                        idempotencyKey = "message:no-device:$userId",
                    ),
                )

            assertNull(disabledByPreference)
            assertNull(disabledByDevice)
            assertEquals(emptyList(), repository.outbox)
        }
}

private class InMemoryNotificationRepository(
    userId: UUID,
) : NotificationRepository {
    var registration: PushDeviceRegistration? = null
    var activeDeviceCount: Int = 1
    var failOutboxWrites: Boolean = false
    val outbox = mutableListOf<NotificationOutboxInsert>()
    val recentNotifications = mutableListOf<RecentNotification>()
    val recentChecks = mutableListOf<RecentNotificationCheck>()
    var preferences: NotificationPreferencesRecord? =
        NotificationPreferencesRecord(
            userId = userId,
            detailLevel = "discreet",
            messagesEnabled = true,
            matchesEnabled = true,
            activityEnabled = true,
            flirtsEnabled = true,
            albumRequestsEnabled = true,
            partnerInvitesEnabled = true,
            systemSafetyEnabled = true,
            quietHoursEnabled = false,
            quietHoursStartMinute = 22 * 60,
            quietHoursEndMinute = 8 * 60,
            quietHoursTimezone = "UTC",
        )

    override suspend fun getPreferences(userId: UUID): NotificationPreferencesRecord? = preferences?.takeIf { it.userId == userId }

    override suspend fun getDeliveryGate(userId: UUID): NotificationDeliveryGate? =
        preferences
            ?.takeIf { it.userId == userId }
            ?.let { NotificationDeliveryGate(preferences = it, activeDeviceCount = activeDeviceCount) }

    override suspend fun updatePreferences(
        userId: UUID,
        update: NotificationPreferencesUpdate,
    ): NotificationPreferencesRecord? {
        val current = preferences?.takeIf { it.userId == userId } ?: return null
        preferences =
            current.copy(
                detailLevel = update.detailLevel ?: current.detailLevel,
                messagesEnabled = update.messagesEnabled ?: current.messagesEnabled,
                matchesEnabled = update.matchesEnabled ?: current.matchesEnabled,
                activityEnabled = update.activityEnabled ?: current.activityEnabled,
                flirtsEnabled = update.flirtsEnabled ?: current.flirtsEnabled,
                albumRequestsEnabled = update.albumRequestsEnabled ?: current.albumRequestsEnabled,
                partnerInvitesEnabled = update.partnerInvitesEnabled ?: current.partnerInvitesEnabled,
                systemSafetyEnabled = update.systemSafetyEnabled ?: current.systemSafetyEnabled,
                quietHoursEnabled = update.quietHoursEnabled ?: current.quietHoursEnabled,
                quietHoursStartMinute = update.quietHoursStartMinute ?: current.quietHoursStartMinute,
                quietHoursEndMinute = update.quietHoursEndMinute ?: current.quietHoursEndMinute,
                quietHoursTimezone = update.quietHoursTimezone ?: current.quietHoursTimezone,
            )
        return preferences
    }

    override suspend fun upsertPushDevice(
        userId: UUID,
        registration: PushDeviceRegistration,
        deviceIdFactory: () -> UUID,
    ): PushDeviceRecord {
        this.registration = registration
        return PushDeviceRecord(
            deviceId = deviceIdFactory(),
            userId = userId,
            platform = registration.platform,
            notificationsEnabled = registration.notificationsEnabled,
            lastSeenAt = Instant.parse("2026-05-01T00:00:00Z"),
        )
    }

    override suspend fun unregisterPushDevice(
        userId: UUID,
        platform: String,
        deviceToken: String,
    ): Boolean = true

    override suspend fun deactivatePushDevice(deviceId: UUID): Boolean = true

    override suspend fun insertOutboxNotification(
        notification: NotificationOutboxInsert,
        notificationIdFactory: () -> UUID,
    ): NotificationOutboxRecord? {
        if (failOutboxWrites) throw java.sql.SQLException("check constraint violated")
        if (outbox.any { it.idempotencyKey == notification.idempotencyKey }) return null
        outbox.add(notification)
        return NotificationOutboxRecord(
            notificationId = notificationIdFactory(),
            recipientUserId = notification.recipientUserId,
            kind = notification.kind,
            status = "pending",
            createdAt = Instant.parse("2026-05-01T00:00:00Z"),
        )
    }

    override suspend fun hasRecentNotification(
        recipientUserId: UUID,
        kind: String,
        throttleKey: String,
        since: Instant,
    ): Boolean {
        recentChecks +=
            RecentNotificationCheck(
                recipientUserId = recipientUserId,
                kind = kind,
                throttleKey = throttleKey,
                since = since,
            )
        return recentNotifications.any {
            it.recipientUserId == recipientUserId &&
                it.kind == kind &&
                it.throttleKey == throttleKey
        }
    }

    override suspend fun claimPendingNotifications(limit: Int): List<NotificationDeliveryRecord> = emptyList()

    override suspend fun requeueStaleProcessingNotifications(
        staleBefore: Instant,
        limit: Int,
    ): Int = 0

    override suspend fun listActivePushTargets(userId: UUID): List<PushDeliveryTarget> = emptyList()

    override suspend fun markNotificationSent(notificationId: UUID): Boolean = false

    override suspend fun markNotificationSkipped(
        notificationId: UUID,
        reason: String,
    ): Boolean = false

    override suspend fun markNotificationRetry(
        notificationId: UUID,
        retryAt: Instant,
        error: String,
    ): Boolean = false

    override suspend fun markNotificationFailed(
        notificationId: UUID,
        error: String,
    ): Boolean = false
}

private data class RecentNotification(
    val recipientUserId: UUID,
    val kind: String,
    val throttleKey: String,
)

private data class RecentNotificationCheck(
    val recipientUserId: UUID,
    val kind: String,
    val throttleKey: String,
    val since: Instant,
)

private val fixedClock: Clock =
    Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC)
