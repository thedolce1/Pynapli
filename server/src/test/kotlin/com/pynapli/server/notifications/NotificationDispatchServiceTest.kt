package com.pynapli.server.notifications

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationDispatchServiceTest {
    @Test
    fun dispatchPendingSendsClaimedNotificationToActiveDevices() =
        runBlocking {
            val recipient = uuid(1)
            val notification = notification(recipientUserId = recipient, attemptCount = 1)
            val repository =
                DispatchRepository(
                    claimed = mutableListOf(notification),
                    targetsByUser = mapOf(recipient to listOf(target(recipient))),
                )
            val metrics = NotificationMetrics()
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.accepted()),
                    clock = fixedClock,
                    metrics = metrics,
                )

            val summary = service.dispatchPending(limit = 10)

            assertEquals(NotificationDispatchSummary(claimed = 1, sent = 1, skipped = 0, retried = 0, failed = 0), summary)
            assertEquals(listOf(notification.notificationId), repository.sent)
            // Privacy-safe metric recorded for the accepted send (outcome/kind/platform only).
            assertEquals(1, metrics.totalFor(NotificationMetrics.Outcome.SENT))
            assertEquals(
                "android",
                metrics.snapshot().single { it.outcome == NotificationMetrics.Outcome.SENT }.platform,
            )
        }

    @Test
    fun dispatchPendingRequeuesStaleProcessingRowsBeforeClaiming() =
        runBlocking {
            val recipient = uuid(11)
            val notification = notification(recipientUserId = recipient, attemptCount = 2)
            val repository =
                DispatchRepository(
                    claimed = mutableListOf(notification),
                    staleProcessingToRecover = 2,
                    targetsByUser = mapOf(recipient to listOf(target(recipient))),
                )
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.accepted()),
                    clock = fixedClock,
                )

            val summary = service.dispatchPending(limit = 10)

            assertEquals(
                NotificationDispatchSummary(claimed = 1, sent = 1, skipped = 0, retried = 0, failed = 0, recovered = 2),
                summary,
            )
            assertEquals(Instant.parse("2026-04-30T23:50:00Z"), repository.staleRecoveryChecks.single().staleBefore)
            assertEquals(10, repository.staleRecoveryChecks.single().limit)
        }

    @Test
    fun dispatchPendingSkipsNotificationWithoutActiveDevices() =
        runBlocking {
            val recipient = uuid(2)
            val notification = notification(recipientUserId = recipient, attemptCount = 1)
            val repository = DispatchRepository(claimed = mutableListOf(notification))
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.accepted()),
                    clock = fixedClock,
                )

            val summary = service.dispatchPending(limit = 10)

            assertEquals(NotificationDispatchSummary(claimed = 1, sent = 0, skipped = 1, retried = 0, failed = 0), summary)
            assertEquals(listOf(notification.notificationId), repository.skipped.map { it.first })
            assertEquals("NO_ACTIVE_PUSH_DEVICES", repository.skipped.single().second)
        }

    @Test
    fun dispatchPendingRetriesTransientDeliveryFailure() =
        runBlocking {
            val recipient = uuid(3)
            val notification = notification(recipientUserId = recipient, attemptCount = 2)
            val repository =
                DispatchRepository(
                    claimed = mutableListOf(notification),
                    targetsByUser = mapOf(recipient to listOf(target(recipient))),
                )
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.retryableFailure("temporary outage")),
                    clock = fixedClock,
                    maxAttempts = 5,
                )

            val summary = service.dispatchPending(limit = 10)

            assertEquals(NotificationDispatchSummary(claimed = 1, sent = 0, skipped = 0, retried = 1, failed = 0), summary)
            val retry = repository.retried.single()
            assertEquals(notification.notificationId, retry.notificationId)
            assertEquals(Instant.parse("2026-05-01T00:00:20Z"), retry.retryAt)
            assertEquals("temporary outage", retry.error)
        }

    @Test
    fun dispatchPendingDefersSocialNotificationDuringQuietHours() =
        runBlocking {
            val recipient = uuid(30)
            val notification = notification(recipientUserId = recipient, attemptCount = 1)
            val repository =
                DispatchRepository(
                    claimed = mutableListOf(notification),
                    preferencesByUser =
                        mapOf(
                            recipient to
                                preferences(
                                    userId = recipient,
                                    quietHoursEnabled = true,
                                    quietHoursStartMinute = 22 * 60,
                                    quietHoursEndMinute = 8 * 60,
                                    quietHoursTimezone = "UTC",
                                ),
                        ),
                    targetsByUser = mapOf(recipient to listOf(target(recipient))),
                )
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.accepted()),
                    clock = fixedClock,
                )

            val summary = service.dispatchPending(limit = 10)

            assertEquals(NotificationDispatchSummary(claimed = 1, sent = 0, skipped = 0, retried = 1, failed = 0), summary)
            assertEquals(emptyList(), repository.sent)
            val retry = repository.retried.single()
            assertEquals(notification.notificationId, retry.notificationId)
            assertEquals(Instant.parse("2026-05-01T08:00:00Z"), retry.retryAt)
            assertEquals("QUIET_HOURS_ACTIVE", retry.error)
        }

    @Test
    fun dispatchPendingSendsSystemSafetyNotificationDuringQuietHours() =
        runBlocking {
            val recipient = uuid(31)
            val notification =
                notification(
                    recipientUserId = recipient,
                    attemptCount = 1,
                    kind = NotificationKind.SYSTEM_SAFETY,
                )
            val repository =
                DispatchRepository(
                    claimed = mutableListOf(notification),
                    preferencesByUser =
                        mapOf(
                            recipient to
                                preferences(
                                    userId = recipient,
                                    quietHoursEnabled = true,
                                    quietHoursStartMinute = 22 * 60,
                                    quietHoursEndMinute = 8 * 60,
                                    quietHoursTimezone = "UTC",
                                ),
                        ),
                    targetsByUser = mapOf(recipient to listOf(target(recipient))),
                )
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.accepted()),
                    clock = fixedClock,
                )

            val summary = service.dispatchPending(limit = 10)

            assertEquals(NotificationDispatchSummary(claimed = 1, sent = 1, skipped = 0, retried = 0, failed = 0), summary)
            assertEquals(listOf(notification.notificationId), repository.sent)
            assertEquals(emptyList(), repository.retried)
        }

    @Test
    fun dispatchPendingFailsTerminalOrExhaustedDeliveryFailure() =
        runBlocking {
            val recipient = uuid(4)
            val notification = notification(recipientUserId = recipient, attemptCount = 5)
            val repository =
                DispatchRepository(
                    claimed = mutableListOf(notification),
                    targetsByUser = mapOf(recipient to listOf(target(recipient))),
                )
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.retryableFailure("still down")),
                    clock = fixedClock,
                    maxAttempts = 5,
                )

            val summary = service.dispatchPending(limit = 10)

            assertEquals(NotificationDispatchSummary(claimed = 1, sent = 0, skipped = 0, retried = 0, failed = 1), summary)
            assertEquals(listOf(notification.notificationId), repository.failed.map { it.first })
            assertEquals("still down", repository.failed.single().second)
        }

    @Test
    fun dispatchPendingDeactivatesDeviceOnTerminalDeliveryFailure() =
        runBlocking {
            val recipient = uuid(7)
            val deadDevice = target(recipient)
            val notification = notification(recipientUserId = recipient, attemptCount = 1)
            val repository =
                DispatchRepository(
                    claimed = mutableListOf(notification),
                    targetsByUser = mapOf(recipient to listOf(deadDevice)),
                )
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.terminalFailure("UNREGISTERED: token dead")),
                    clock = fixedClock,
                    maxAttempts = 5,
                )

            val summary = service.dispatchPending(limit = 10)

            // The token's device is deactivated so it is never retried, and the notification fails
            // terminally (its only target was permanently invalid).
            assertEquals(listOf(deadDevice.deviceId), repository.deactivatedDevices)
            assertEquals(1, summary.failed)
        }

    @Test
    fun dispatchPendingDoesNotDeactivateDeviceOnRetryableFailure() =
        runBlocking {
            val recipient = uuid(8)
            val notification = notification(recipientUserId = recipient, attemptCount = 1)
            val repository =
                DispatchRepository(
                    claimed = mutableListOf(notification),
                    targetsByUser = mapOf(recipient to listOf(target(recipient))),
                )
            val service =
                NotificationDispatchService(
                    repository = repository,
                    sender = StaticPushSender(PushSendResult.retryableFailure("temporary outage")),
                    clock = fixedClock,
                    maxAttempts = 5,
                )

            service.dispatchPending(limit = 10)

            // A transient failure must NOT deactivate the device — only permanent token failures do.
            assertEquals(emptyList(), repository.deactivatedDevices)
        }
}

private class DispatchRepository(
    private val claimed: MutableList<NotificationDeliveryRecord> = mutableListOf(),
    private val preferencesByUser: Map<UUID, NotificationPreferencesRecord> = emptyMap(),
    private val targetsByUser: Map<UUID, List<PushDeliveryTarget>> = emptyMap(),
    private val staleProcessingToRecover: Int = 0,
) : NotificationRepository {
    val sent = mutableListOf<UUID>()
    val skipped = mutableListOf<Pair<UUID, String>>()
    val retried = mutableListOf<RetryRecord>()
    val failed = mutableListOf<Pair<UUID, String>>()
    val staleRecoveryChecks = mutableListOf<StaleRecoveryCheck>()
    val deactivatedDevices = mutableListOf<UUID>()

    override suspend fun claimPendingNotifications(limit: Int): List<NotificationDeliveryRecord> {
        val rows = claimed.take(limit)
        claimed.removeAll(rows.toSet())
        return rows
    }

    override suspend fun listActivePushTargets(userId: UUID): List<PushDeliveryTarget> = targetsByUser[userId].orEmpty()

    override suspend fun requeueStaleProcessingNotifications(
        staleBefore: Instant,
        limit: Int,
    ): Int {
        staleRecoveryChecks += StaleRecoveryCheck(staleBefore, limit)
        return staleProcessingToRecover
    }

    override suspend fun markNotificationSent(notificationId: UUID): Boolean {
        sent += notificationId
        return true
    }

    override suspend fun markNotificationSkipped(
        notificationId: UUID,
        reason: String,
    ): Boolean {
        skipped += notificationId to reason
        return true
    }

    override suspend fun markNotificationRetry(
        notificationId: UUID,
        retryAt: Instant,
        error: String,
    ): Boolean {
        retried += RetryRecord(notificationId, retryAt, error)
        return true
    }

    override suspend fun markNotificationFailed(
        notificationId: UUID,
        error: String,
    ): Boolean {
        failed += notificationId to error
        return true
    }

    override suspend fun getPreferences(userId: UUID): NotificationPreferencesRecord? = preferencesByUser[userId]

    override suspend fun getDeliveryGate(userId: UUID): NotificationDeliveryGate? = null

    override suspend fun updatePreferences(
        userId: UUID,
        update: NotificationPreferencesUpdate,
    ): NotificationPreferencesRecord? = null

    override suspend fun upsertPushDevice(
        userId: UUID,
        registration: PushDeviceRegistration,
        deviceIdFactory: () -> UUID,
    ): PushDeviceRecord = throw UnsupportedOperationException("not used")

    override suspend fun unregisterPushDevice(
        userId: UUID,
        platform: String,
        deviceToken: String,
    ): Boolean = false

    override suspend fun deactivatePushDevice(deviceId: UUID): Boolean {
        deactivatedDevices += deviceId
        return true
    }

    override suspend fun insertOutboxNotification(
        notification: NotificationOutboxInsert,
        notificationIdFactory: () -> UUID,
    ): NotificationOutboxRecord? = null

    override suspend fun hasRecentNotification(
        recipientUserId: UUID,
        kind: String,
        throttleKey: String,
        since: Instant,
    ): Boolean = false
}

private class StaticPushSender(
    private val result: PushSendResult,
) : PushNotificationSender {
    override suspend fun send(
        target: PushDeliveryTarget,
        notification: NotificationDeliveryRecord,
    ): PushSendResult = result
}

private data class RetryRecord(
    val notificationId: UUID,
    val retryAt: Instant,
    val error: String,
)

private data class StaleRecoveryCheck(
    val staleBefore: Instant,
    val limit: Int,
)

private fun notification(
    recipientUserId: UUID,
    attemptCount: Int,
    kind: String = NotificationKind.MESSAGE,
): NotificationDeliveryRecord =
    NotificationDeliveryRecord(
        notificationId = uuid(100),
        recipientUserId = recipientUserId,
        actorUserId = uuid(200),
        kind = kind,
        subjectId = uuid(300),
        title = "Pynapli",
        body = "You have a new update.",
        payloadJson = """{"kind":"message"}""",
        attemptCount = attemptCount,
        createdAt = Instant.parse("2026-05-01T00:00:00Z"),
    )

private fun preferences(
    userId: UUID,
    quietHoursEnabled: Boolean = false,
    quietHoursStartMinute: Int = 22 * 60,
    quietHoursEndMinute: Int = 8 * 60,
    quietHoursTimezone: String = "UTC",
): NotificationPreferencesRecord =
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
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinute = quietHoursStartMinute,
        quietHoursEndMinute = quietHoursEndMinute,
        quietHoursTimezone = quietHoursTimezone,
    )

private fun target(userId: UUID): PushDeliveryTarget =
    PushDeliveryTarget(
        deviceId = uuid(400),
        userId = userId,
        platform = "android",
        deviceToken = "push-token-${userId.toString().take(16)}",
    )

private val fixedClock: Clock =
    Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC)

private fun uuid(value: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
