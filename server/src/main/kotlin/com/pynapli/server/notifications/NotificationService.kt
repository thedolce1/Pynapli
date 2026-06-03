package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.NotificationDetailLevel
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.PushDeviceDto
import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

interface NotificationPublisher {
    suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord?
}

class NotificationService(
    private val repository: NotificationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : NotificationPublisher {
    suspend fun getPreferences(userId: UUID): NotificationPreferencesDto =
        repository.getPreferences(userId)?.toDto()
            ?: throw NotificationException("NOTIFICATION_PREFERENCES_NOT_FOUND")

    suspend fun updatePreferences(
        userId: UUID,
        request: UpdateNotificationPreferencesRequestDto,
    ): NotificationPreferencesDto {
        val current =
            repository.getPreferences(userId)
                ?: throw NotificationException("NOTIFICATION_PREFERENCES_NOT_FOUND")
        val detailLevel =
            request.detailLevel?.trim()?.lowercase()?.also { detail ->
                if (detail !in detailLevels) throw NotificationException("INVALID_NOTIFICATION_DETAIL_LEVEL")
            }
        val quietHoursStartMinute =
            request.quietHoursStartMinute?.also { minute ->
                if (minute !in MIN_QUIET_HOURS_MINUTE..MAX_QUIET_HOURS_MINUTE) {
                    throw NotificationException("INVALID_QUIET_HOURS_WINDOW")
                }
            }
        val quietHoursEndMinute =
            request.quietHoursEndMinute?.also { minute ->
                if (minute !in MIN_QUIET_HOURS_MINUTE..MAX_QUIET_HOURS_MINUTE) {
                    throw NotificationException("INVALID_QUIET_HOURS_WINDOW")
                }
            }
        val effectiveQuietHoursEnabled = request.quietHoursEnabled ?: current.quietHoursEnabled
        val effectiveQuietHoursStartMinute = quietHoursStartMinute ?: current.quietHoursStartMinute
        val effectiveQuietHoursEndMinute = quietHoursEndMinute ?: current.quietHoursEndMinute
        if (effectiveQuietHoursEnabled && effectiveQuietHoursStartMinute == effectiveQuietHoursEndMinute) {
            throw NotificationException("INVALID_QUIET_HOURS_WINDOW")
        }
        val quietHoursTimezone =
            request.quietHoursTimezone?.trim()?.takeIf { it.isNotEmpty() }?.also { timezone ->
                if (timezone.length > MAX_TIMEZONE_LENGTH || runCatching { ZoneId.of(timezone) }.isFailure) {
                    throw NotificationException("INVALID_QUIET_HOURS_TIMEZONE")
                }
            }

        return repository
            .updatePreferences(
                userId = userId,
                update =
                    NotificationPreferencesUpdate(
                        detailLevel = detailLevel,
                        messagesEnabled = request.messagesEnabled,
                        matchesEnabled = request.matchesEnabled,
                        activityEnabled = request.activityEnabled,
                        flirtsEnabled = request.flirtsEnabled,
                        albumRequestsEnabled = request.albumRequestsEnabled,
                        partnerInvitesEnabled = request.partnerInvitesEnabled,
                        systemSafetyEnabled = request.systemSafetyEnabled,
                        quietHoursEnabled = request.quietHoursEnabled,
                        quietHoursStartMinute = quietHoursStartMinute,
                        quietHoursEndMinute = quietHoursEndMinute,
                        quietHoursTimezone = quietHoursTimezone,
                    ),
            )?.toDto() ?: throw NotificationException("NOTIFICATION_PREFERENCES_NOT_FOUND")
    }

    suspend fun registerPushDevice(
        userId: UUID,
        request: RegisterPushDeviceRequestDto,
    ): PushDeviceRegistrationResponseDto {
        val platform = request.platform.trim().lowercase()
        if (platform !in platforms) throw NotificationException("INVALID_PUSH_PLATFORM")

        val token = request.deviceToken.trim()
        if (token.length !in MIN_PUSH_TOKEN_LENGTH..MAX_PUSH_TOKEN_LENGTH) {
            throw NotificationException("INVALID_PUSH_TOKEN")
        }

        val record =
            repository.upsertPushDevice(
                userId = userId,
                registration =
                    PushDeviceRegistration(
                        platform = platform,
                        deviceToken = token,
                        appVersion = request.appVersion.cleaned(MAX_APP_VERSION_LENGTH),
                        locale = request.locale.cleaned(MAX_LOCALE_LENGTH),
                        timezone = request.timezone.cleaned(MAX_TIMEZONE_LENGTH),
                        notificationsEnabled = request.notificationsEnabled,
                    ),
                deviceIdFactory = UUID::randomUUID,
            )

        return PushDeviceRegistrationResponseDto(record.toDto())
    }

    suspend fun unregisterPushDevice(
        userId: UUID,
        request: UnregisterPushDeviceRequestDto,
    ) {
        val platform = request.platform.trim().lowercase()
        if (platform !in platforms) throw NotificationException("INVALID_PUSH_PLATFORM")

        val token = request.deviceToken.trim()
        if (token.length !in MIN_PUSH_TOKEN_LENGTH..MAX_PUSH_TOKEN_LENGTH) {
            throw NotificationException("INVALID_PUSH_TOKEN")
        }

        repository.unregisterPushDevice(
            userId = userId,
            platform = platform,
            deviceToken = token,
        )
    }

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        if (notification.actorUserId == notification.recipientUserId) return null
        val kind = notification.kind.trim().lowercase()
        if (kind !in notificationKinds) throw NotificationException("INVALID_NOTIFICATION_KIND")

        val gate = repository.getDeliveryGate(notification.recipientUserId) ?: return null
        if (gate.activeDeviceCount <= 0) return null
        if (!gate.preferences.allows(kind)) return null

        val throttlePolicy = notification.throttlePolicy(kind)
        if (
            throttlePolicy != null &&
            repository.hasRecentNotification(
                recipientUserId = notification.recipientUserId,
                kind = kind,
                throttleKey = throttlePolicy.key,
                since = clock.instant().minusSeconds(throttlePolicy.windowSeconds),
            )
        ) {
            return null
        }

        val copy = notificationCopy(kind = kind, detailLevel = gate.preferences.detailLevel)
        val idempotencyKey =
            throttlePolicy?.bucketedIdempotencyKey(clock.instant().epochSecond)
                ?: notification.idempotencyKey
        return repository.insertOutboxNotification(
            notification =
                NotificationOutboxInsert(
                    recipientUserId = notification.recipientUserId,
                    actorUserId = notification.actorUserId,
                    kind = kind,
                    subjectId = notification.subjectId,
                    idempotencyKey = idempotencyKey,
                    throttleKey = throttlePolicy?.key,
                    title = copy.title,
                    body = copy.body,
                    payloadJson = notification.payload.toPayloadJson(kind, gate.preferences.detailLevel),
                ),
            notificationIdFactory = UUID::randomUUID,
        )
    }

    private fun NotificationPreferencesRecord.toDto(): NotificationPreferencesDto =
        NotificationPreferencesDto(
            detailLevel = detailLevel,
            messagesEnabled = messagesEnabled,
            matchesEnabled = matchesEnabled,
            activityEnabled = activityEnabled,
            flirtsEnabled = flirtsEnabled,
            albumRequestsEnabled = albumRequestsEnabled,
            partnerInvitesEnabled = partnerInvitesEnabled,
            systemSafetyEnabled = systemSafetyEnabled,
            quietHoursEnabled = quietHoursEnabled,
            quietHoursStartMinute = quietHoursStartMinute,
            quietHoursEndMinute = quietHoursEndMinute,
            quietHoursTimezone = quietHoursTimezone,
        )

    private fun PushDeviceRecord.toDto(): PushDeviceDto =
        PushDeviceDto(
            deviceId = deviceId.toString(),
            platform = platform,
            notificationsEnabled = notificationsEnabled,
            lastSeenAt = lastSeenAt.toString(),
        )

    private fun String?.cleaned(maxLength: Int): String? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return value.take(maxLength)
    }

    private fun NotificationPreferencesRecord.allows(kind: String): Boolean =
        when (kind) {
            NotificationKind.MESSAGE -> messagesEnabled
            NotificationKind.MATCH -> matchesEnabled
            NotificationKind.FLIRT -> activityEnabled && flirtsEnabled
            NotificationKind.PROFILE_VIEW -> activityEnabled
            NotificationKind.ALBUM_REQUEST -> activityEnabled && albumRequestsEnabled
            NotificationKind.PROFILE_LINK_INVITE,
            NotificationKind.PROFILE_LINK_ACCEPTED,
            NotificationKind.PARTNER_CHAT_INVITE,
            NotificationKind.TONIGHT_INVITE,
            -> partnerInvitesEnabled
            NotificationKind.TONIGHT_REMINDER -> activityEnabled
            NotificationKind.SYSTEM_SAFETY -> systemSafetyEnabled
            else -> false
        }

    private fun notificationCopy(
        kind: String,
        detailLevel: String,
    ): NotificationCopy {
        if (detailLevel == NotificationDetailLevel.DISCREET) {
            return NotificationCopy(
                title = "Pynapli",
                body = "You have a new update.",
            )
        }

        return when (kind) {
            NotificationKind.MESSAGE -> NotificationCopy("New message", "Someone sent you a message.")
            NotificationKind.MATCH -> NotificationCopy("New match", "You have a new match.")
            NotificationKind.FLIRT -> NotificationCopy("New flirt", "Someone flirted with you.")
            NotificationKind.PROFILE_VIEW -> NotificationCopy("New activity", "Someone viewed your profile.")
            NotificationKind.ALBUM_REQUEST ->
                NotificationCopy(
                    "Private album request",
                    "Someone requested access to your private album.",
                )
            NotificationKind.PROFILE_LINK_INVITE ->
                NotificationCopy(
                    "Profile link invite",
                    "Someone invited you to link profiles.",
                )
            NotificationKind.PROFILE_LINK_ACCEPTED ->
                NotificationCopy(
                    "Profile link accepted",
                    "Your profile link invite was accepted.",
                )
            NotificationKind.PARTNER_CHAT_INVITE ->
                NotificationCopy(
                    "Chat invite",
                    "Your linked partner invited you to a chat.",
                )
            NotificationKind.TONIGHT_INVITE ->
                NotificationCopy(
                    "Playing Tonight invite",
                    "Your linked partner wants to go live tonight.",
                )
            NotificationKind.TONIGHT_REMINDER ->
                NotificationCopy(
                    "Playing Tonight ending soon",
                    "Your session ends in about 15 minutes.",
                )
            NotificationKind.SYSTEM_SAFETY -> NotificationCopy("Pynapli safety", "You have a safety update.")
            else -> NotificationCopy("Pynapli", "You have a new update.")
        }
    }

    private fun Map<String, String>.toPayloadJson(
        kind: String,
        detailLevel: String,
    ): String {
        val payload =
            buildMap<String, JsonElement> {
                put("kind", JsonPrimitive(kind))
                put("detailLevel", JsonPrimitive(detailLevel))
                this@toPayloadJson.forEach { (key, value) ->
                    put(key, JsonPrimitive(value))
                }
            }
        return JsonObject(payload).toString()
    }

    private fun NotificationEventDraft.throttlePolicy(kind: String): NotificationThrottlePolicy? =
        when (kind) {
            NotificationKind.MESSAGE ->
                subjectId?.let { conversationId ->
                    NotificationThrottlePolicy(
                        key = "message:$recipientUserId:conversation:$conversationId",
                        windowSeconds = MESSAGE_THREAD_WINDOW_SECONDS,
                    )
                }
            NotificationKind.FLIRT ->
                actorUserId?.let { actorUserId ->
                    NotificationThrottlePolicy(
                        key = "flirt:$recipientUserId:actor:$actorUserId",
                        windowSeconds = SOCIAL_ACTOR_WINDOW_SECONDS,
                    )
                }
            NotificationKind.PROFILE_VIEW ->
                NotificationThrottlePolicy(
                    key = "profile_view:$recipientUserId",
                    windowSeconds = PROFILE_VIEW_WINDOW_SECONDS,
                )
            NotificationKind.ALBUM_REQUEST ->
                actorUserId?.let { actorUserId ->
                    NotificationThrottlePolicy(
                        key = "album_request:$recipientUserId:actor:$actorUserId",
                        windowSeconds = SOCIAL_ACTOR_WINDOW_SECONDS,
                    )
                }
            else -> null
        }

    companion object {
        private const val MIN_PUSH_TOKEN_LENGTH = 16
        private const val MAX_PUSH_TOKEN_LENGTH = 4096
        private const val MAX_APP_VERSION_LENGTH = 40
        private const val MAX_LOCALE_LENGTH = 32
        private const val MAX_TIMEZONE_LENGTH = 64
        private const val MIN_QUIET_HOURS_MINUTE = 0
        private const val MAX_QUIET_HOURS_MINUTE = 1439
        private const val MESSAGE_THREAD_WINDOW_SECONDS = 90L
        private const val SOCIAL_ACTOR_WINDOW_SECONDS = 6L * 60L * 60L
        private const val PROFILE_VIEW_WINDOW_SECONDS = 6L * 60L * 60L

        private val detailLevels =
            setOf(
                NotificationDetailLevel.DISCREET,
                NotificationDetailLevel.STANDARD,
            )
        private val platforms = setOf("android", "ios")
        private val notificationKinds =
            setOf(
                NotificationKind.MESSAGE,
                NotificationKind.MATCH,
                NotificationKind.FLIRT,
                NotificationKind.PROFILE_VIEW,
                NotificationKind.ALBUM_REQUEST,
                NotificationKind.PROFILE_LINK_INVITE,
                NotificationKind.PROFILE_LINK_ACCEPTED,
                NotificationKind.PARTNER_CHAT_INVITE,
                NotificationKind.TONIGHT_INVITE,
                NotificationKind.TONIGHT_REMINDER,
                NotificationKind.SYSTEM_SAFETY,
            )
    }
}

private data class NotificationThrottlePolicy(
    val key: String,
    val windowSeconds: Long,
)

class NotificationException(
    val code: String,
) : RuntimeException(code)

object NotificationKind {
    const val MESSAGE = "message"
    const val MATCH = "match"
    const val FLIRT = "flirt"
    const val PROFILE_VIEW = "profile_view"
    const val ALBUM_REQUEST = "album_request"
    const val PROFILE_LINK_INVITE = "profile_link_invite"
    const val PROFILE_LINK_ACCEPTED = "profile_link_accepted"
    const val PARTNER_CHAT_INVITE = "partner_chat_invite"
    const val TONIGHT_INVITE = "tonight_invite"
    const val TONIGHT_REMINDER = "tonight_reminder"
    const val SYSTEM_SAFETY = "system_safety"
}

data class NotificationEventDraft(
    val recipientUserId: UUID,
    val actorUserId: UUID?,
    val kind: String,
    val subjectId: UUID?,
    val idempotencyKey: String,
    val payload: Map<String, String> = emptyMap(),
)

private data class NotificationCopy(
    val title: String,
    val body: String,
)

private fun NotificationThrottlePolicy.bucketedIdempotencyKey(epochSecond: Long): String = "$key:${epochSecond / windowSeconds}"
