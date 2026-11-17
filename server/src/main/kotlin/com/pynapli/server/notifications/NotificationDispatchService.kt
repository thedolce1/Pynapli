package com.pynapli.server.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.min

class NotificationDispatchService(
    private val repository: NotificationRepository,
    private val sender: PushNotificationSender,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAttempts: Int = 5,
    private val metrics: NotificationMetrics = NotificationMetrics(),
) {
    private val logger = LoggerFactory.getLogger(NotificationDispatchService::class.java)

    /** Exposes the privacy-safe delivery metrics recorder for snapshotting/export. */
    fun metrics(): NotificationMetrics = metrics

    suspend fun dispatchPending(limit: Int = DEFAULT_LIMIT): NotificationDispatchSummary {
        val normalizedLimit = limit.coerceIn(1, MAX_LIMIT)
        val recovered =
            repository.requeueStaleProcessingNotifications(
                staleBefore = clock.instant().minusSeconds(PROCESSING_STALE_SECONDS),
                limit = normalizedLimit,
            )
        val notifications = repository.claimPendingNotifications(normalizedLimit)
        var sent = 0
        var skipped = 0
        var retried = 0
        var failed = 0

        notifications.forEach { notification ->
            when (dispatchOne(notification)) {
                DispatchOutcome.Sent -> sent++
                DispatchOutcome.Skipped -> skipped++
                DispatchOutcome.Retried -> retried++
                DispatchOutcome.Failed -> failed++
            }
        }

        return NotificationDispatchSummary(
            claimed = notifications.size,
            sent = sent,
            skipped = skipped,
            retried = retried,
            failed = failed,
            recovered = recovered,
        )
    }

    private suspend fun dispatchOne(notification: NotificationDeliveryRecord): DispatchOutcome {
        val quietHoursRelease =
            repository
                .getPreferences(notification.recipientUserId)
                ?.quietHoursRelease(notification.kind, clock.instant())
        if (quietHoursRelease != null && notification.kind in TIME_SENSITIVE_KINDS) {
            // "Playing tonight" and live Psync invites are about the next hour or two: held until
            // morning they would point at a session that is long over, so they are dropped instead.
            repository.markNotificationSkipped(notification.notificationId, QUIET_HOURS_STALE)
            metrics.record(NotificationMetrics.Outcome.SKIPPED, notification.kind)
            return DispatchOutcome.Skipped
        }
        if (quietHoursRelease != null) {
            repository.markNotificationRetry(
                notificationId = notification.notificationId,
                retryAt = quietHoursRelease,
                error = QUIET_HOURS_ACTIVE,
            )
            metrics.record(NotificationMetrics.Outcome.RETRIED, notification.kind)
            return DispatchOutcome.Retried
        }

        val targets = repository.listActivePushTargets(notification.recipientUserId)
        if (targets.isEmpty()) {
            repository.markNotificationSkipped(notification.notificationId, NO_ACTIVE_DEVICES)
            metrics.record(NotificationMetrics.Outcome.SKIPPED, notification.kind)
            return DispatchOutcome.Skipped
        }

        val results =
            targets.map { target ->
                target to
                    runCatching { sender.send(target, notification) }.getOrElse { error ->
                        PushSendResult.retryableFailure(error.message ?: error::class.simpleName.orEmpty())
                    }
            }

        // Device-token lifecycle: a terminal failure means the provider considers this token
        // permanently invalid (e.g. FCM UNREGISTERED). Deactivate that device so its dead token is
        // never retried on future notifications. This is independent of the notification's own
        // retry/fail outcome below (a sibling device may still have accepted it).
        results
            .filter { (_, result) -> !result.accepted && result.terminalFailure }
            .forEach { (target, _) ->
                runCatching { repository.deactivatePushDevice(target.deviceId) }
                    .onFailure { error ->
                        logger.warn("Failed to deactivate dead push device {}", target.deviceId, error)
                    }
                metrics.record(NotificationMetrics.Outcome.DEVICE_DEACTIVATED, notification.kind, target.platform)
            }

        val acceptedTarget = results.firstOrNull { (_, result) -> result.accepted }
        if (acceptedTarget != null) {
            repository.markNotificationSent(notification.notificationId)
            metrics.record(NotificationMetrics.Outcome.SENT, notification.kind, acceptedTarget.first.platform)
            return DispatchOutcome.Sent
        }

        val error = results.mapNotNull { (_, result) -> result.errorMessage }.joinToString("; ").ifBlank { "Push delivery failed." }
        val shouldFail = notification.attemptCount >= maxAttempts || results.all { (_, result) -> result.terminalFailure }
        return if (shouldFail) {
            repository.markNotificationFailed(notification.notificationId, error)
            metrics.record(NotificationMetrics.Outcome.FAILED, notification.kind)
            DispatchOutcome.Failed
        } else {
            repository.markNotificationRetry(
                notificationId = notification.notificationId,
                retryAt = clock.instant().plusSeconds(retryDelaySeconds(notification.attemptCount)),
                error = error,
            )
            metrics.record(NotificationMetrics.Outcome.RETRIED, notification.kind)
            DispatchOutcome.Retried
        }
    }

    private fun retryDelaySeconds(attemptCount: Int): Long {
        val shift = (attemptCount - 1).coerceIn(0, 5)
        return min(300L, 10L * (1L shl shift))
    }

    private fun NotificationPreferencesRecord.quietHoursRelease(
        kind: String,
        now: Instant,
    ): Instant? {
        if (!quietHoursEnabled || kind == NotificationKind.SYSTEM_SAFETY) return null
        if (quietHoursStartMinute == quietHoursEndMinute) return null

        val zone = runCatching { ZoneId.of(quietHoursTimezone) }.getOrElse { ZoneId.of("UTC") }
        val localNow = ZonedDateTime.ofInstant(now, zone)
        val minuteOfDay = localNow.hour * 60 + localNow.minute
        val inQuietHours =
            if (quietHoursStartMinute < quietHoursEndMinute) {
                minuteOfDay in quietHoursStartMinute until quietHoursEndMinute
            } else {
                minuteOfDay >= quietHoursStartMinute || minuteOfDay < quietHoursEndMinute
            }
        if (!inQuietHours) return null

        val releaseDate =
            if (quietHoursStartMinute < quietHoursEndMinute || minuteOfDay < quietHoursEndMinute) {
                localNow.toLocalDate()
            } else {
                localNow.toLocalDate().plusDays(1)
            }
        // The local wall-clock end time on that date. (Start of day + N minutes is an hour off on a
        // daylight-saving change day; a nonexistent local time moves forward past the gap.)
        return ZonedDateTime
            .of(releaseDate, LocalTime.of(quietHoursEndMinute / 60, quietHoursEndMinute % 60), zone)
            .toInstant()
    }

    private enum class DispatchOutcome {
        Sent,
        Skipped,
        Retried,
        Failed,
    }

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 250
        private const val PROCESSING_STALE_SECONDS = 10L * 60L
        private const val NO_ACTIVE_DEVICES = "NO_ACTIVE_PUSH_DEVICES"
        private const val QUIET_HOURS_ACTIVE = "QUIET_HOURS_ACTIVE"
        private const val QUIET_HOURS_STALE = "QUIET_HOURS_STALE"
        private val TIME_SENSITIVE_KINDS =
            setOf(
                NotificationKind.TONIGHT_INVITE,
                NotificationKind.TONIGHT_REMINDER,
                NotificationKind.TONIGHT_ACCEPTED,
                NotificationKind.TONIGHT_DECLINED,
                NotificationKind.TONIGHT_ENDED,
                NotificationKind.PSYNC_INVITE,
            )
    }
}

class NotificationDispatchWorker(
    private val service: NotificationDispatchService,
    private val intervalMillis: Long,
    private val batchSize: Int,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(NotificationDispatchWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Exposes the privacy-safe delivery metrics recorder for the admin metrics scrape endpoint. */
    fun metrics(): NotificationMetrics = service.metrics()

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        val summary = service.dispatchPending(batchSize)
                        if (summary.claimed > 0 || summary.recovered > 0) {
                            logger.info(
                                "Notification dispatch recovered={} claimed={} sent={} skipped={} retried={} failed={}",
                                summary.recovered,
                                summary.claimed,
                                summary.sent,
                                summary.skipped,
                                summary.retried,
                                summary.failed,
                            )
                        }
                    }.onFailure { error ->
                        logger.warn("Notification dispatch failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    override fun close() {
        // Emit a final privacy-safe delivery snapshot (counts only — no recipients/bodies/tokens).
        val snapshot = service.metrics().snapshot()
        if (snapshot.isNotEmpty()) {
            logger.info(
                "Notification delivery metrics snapshot: {}",
                snapshot.joinToString(", ") { "${it.outcome}/${it.kind}/${it.platform}=${it.count}" },
            )
        }
        scope.cancel()
    }
}

interface PushNotificationSender {
    suspend fun send(
        target: PushDeliveryTarget,
        notification: NotificationDeliveryRecord,
    ): PushSendResult
}

class LocalLoggingPushNotificationSender : PushNotificationSender {
    private val logger = LoggerFactory.getLogger(LocalLoggingPushNotificationSender::class.java)

    override suspend fun send(
        target: PushDeliveryTarget,
        notification: NotificationDeliveryRecord,
    ): PushSendResult {
        logger.info(
            "Local push accepted notification={} kind={} platform={} device={}",
            notification.notificationId,
            notification.kind,
            target.platform,
            target.deviceId,
        )
        return PushSendResult.accepted()
    }
}

data class PushSendResult(
    val accepted: Boolean,
    val terminalFailure: Boolean = false,
    val errorMessage: String? = null,
) {
    companion object {
        fun accepted(): PushSendResult = PushSendResult(accepted = true)

        fun retryableFailure(errorMessage: String): PushSendResult = PushSendResult(accepted = false, terminalFailure = false, errorMessage = errorMessage)

        fun terminalFailure(errorMessage: String): PushSendResult = PushSendResult(accepted = false, terminalFailure = true, errorMessage = errorMessage)
    }
}

data class NotificationDispatchSummary(
    val claimed: Int,
    val sent: Int,
    val skipped: Int,
    val retried: Int,
    val failed: Int,
    val recovered: Int = 0,
) {
    val completed: Int
        get() = sent + skipped + failed
}
