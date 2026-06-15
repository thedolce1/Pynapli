package com.pynapli.server.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.pynapli.server.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

class FirebaseAdminPushNotificationSender private constructor(
    private val firebaseApp: FirebaseApp,
) : PushNotificationSender {
    private val logger = LoggerFactory.getLogger(FirebaseAdminPushNotificationSender::class.java)

    override suspend fun send(
        target: PushDeliveryTarget,
        notification: NotificationDeliveryRecord,
    ): PushSendResult =
        withContext(Dispatchers.IO) {
            val message =
                Message
                    .builder()
                    .setToken(target.deviceToken)
                    .setNotification(
                        Notification
                            .builder()
                            .setTitle(notification.title)
                            .setBody(notification.body)
                            .build(),
                    ).putAllData(notification.toDataPayload(target))
                    .setAndroidConfig(androidConfigFor(notification.kind))
                    .setApnsConfig(apnsConfigFor(notification.kind))
                    .build()

            runCatching {
                FirebaseMessaging.getInstance(firebaseApp).send(message)
            }.fold(
                onSuccess = { messageId ->
                    logger.info(
                        "FCM accepted notification={} kind={} platform={} device={} messageId={}",
                        notification.notificationId,
                        notification.kind,
                        target.platform,
                        target.deviceId,
                        messageId,
                    )
                    PushSendResult.accepted()
                },
                onFailure = { error ->
                    if (error is FirebaseMessagingException) {
                        error.toPushSendResult()
                    } else {
                        PushSendResult.retryableFailure(error.message ?: error::class.simpleName.orEmpty())
                    }
                },
            )
        }

    private fun NotificationDeliveryRecord.toDataPayload(target: PushDeliveryTarget): Map<String, String> =
        buildMap {
            put("notificationId", notificationId.toString())
            put("recipientUserId", recipientUserId.toString())
            put("kind", kind)
            put("platform", target.platform)
            put("createdAt", createdAt.toString())
            subjectId?.let { put("subjectId", it.toString()) }
            actorUserId?.let { put("actorUserId", it.toString()) }
            put("payload", payloadJson)
        }

    private fun FirebaseMessagingException.toPushSendResult(): PushSendResult {
        val code = messagingErrorCode?.name ?: errorCode?.name ?: "UNKNOWN"
        val message = "$code: ${this.message ?: "FCM delivery failed."}"
        return if (code in terminalErrorCodes) {
            PushSendResult.terminalFailure(message)
        } else {
            PushSendResult.retryableFailure(message)
        }
    }

    companion object {
        private const val APP_NAME = "pynapli-server"

        private val terminalErrorCodes =
            setOf(
                "INVALID_ARGUMENT",
                "UNREGISTERED",
                "SENDER_ID_MISMATCH",
                "THIRD_PARTY_AUTH_ERROR",
            )

        fun fromConfig(config: ServerConfig): FirebaseAdminPushNotificationSender {
            val credentials = resolveFirebaseCredentials(config)
            val options =
                FirebaseOptions
                    .builder()
                    .setCredentials(credentials)
                    .apply { config.firebaseProjectId?.let(::setProjectId) }
                    .build()

            val app =
                FirebaseApp.getApps().firstOrNull { it.name == APP_NAME }
                    ?: FirebaseApp.initializeApp(options, APP_NAME)
            return FirebaseAdminPushNotificationSender(app)
        }
    }
}

/**
 * Which credential source the Firebase Admin SDK should use, in precedence order. An explicit
 * service-account (inline JSON or a file path) wins; otherwise the server falls back to Application
 * Default Credentials — the keyless path that picks up `gcloud auth application-default login`
 * locally and the attached service account on Cloud Run. Kept pure so the precedence is unit-testable.
 */
internal enum class FirebaseCredentialSource { INLINE_JSON, FILE_PATH, APPLICATION_DEFAULT }

internal fun firebaseCredentialSource(config: ServerConfig): FirebaseCredentialSource =
    when {
        config.firebaseServiceAccountJson != null -> FirebaseCredentialSource.INLINE_JSON
        config.firebaseServiceAccountPath != null -> FirebaseCredentialSource.FILE_PATH
        else -> FirebaseCredentialSource.APPLICATION_DEFAULT
    }

internal fun resolveFirebaseCredentials(
    config: ServerConfig,
    applicationDefault: () -> GoogleCredentials = { GoogleCredentials.getApplicationDefault() },
): GoogleCredentials =
    when (firebaseCredentialSource(config)) {
        FirebaseCredentialSource.INLINE_JSON ->
            ByteArrayInputStream(config.firebaseServiceAccountJson!!.toByteArray(StandardCharsets.UTF_8))
                .use(GoogleCredentials::fromStream)
        FirebaseCredentialSource.FILE_PATH ->
            FileInputStream(config.firebaseServiceAccountPath!!).use(GoogleCredentials::fromStream)
        FirebaseCredentialSource.APPLICATION_DEFAULT -> applicationDefault()
    }

/**
 * Platform-agnostic push-delivery decisions for a notification kind, resolved by [forKind] so the
 * rules are unit-testable without constructing Firebase objects (whose builders are write-only). The
 * Firebase APNs/Android configs are a thin translation of these values.
 *
 *  - [highUrgency]: high-urgency kinds get immediate/heads-up delivery; others are power-considerate.
 *  - [apnsPriority]: APNs `apns-priority` header (10 immediate, 5 throttled).
 *  - [collapseKey]: per-kind id so a burst of the same kind coalesces (APNs caps it at 64 bytes).
 */
internal data class PushDeliveryConfig(
    val highUrgency: Boolean,
    val collapseKey: String,
) {
    val apnsPriority: String get() = if (highUrgency) "10" else "5"

    companion object {
        private val highUrgencyKinds =
            setOf(
                NotificationKind.SYSTEM_SAFETY,
                NotificationKind.MESSAGE,
                NotificationKind.MATCH,
                NotificationKind.PARTNER_CHAT_INVITE,
                NotificationKind.TONIGHT_INVITE,
            )

        fun forKind(kind: String): PushDeliveryConfig =
            PushDeliveryConfig(
                highUrgency = kind in highUrgencyKinds,
                collapseKey = kind.ifBlank { "notification" }.take(64),
            )
    }
}

/**
 * APNs delivery config for an FCM message. FCM is the APNs transport, so iOS rendering (sound, badge,
 * lock-screen behaviour, notification-service-extension support) is controlled here rather than in
 * Swift. `mutable-content=1` lets a future iOS notification-service extension localise/redact the
 * payload; the data payload travels in the FCM message alongside `aps`.
 */
internal fun apnsConfigFor(kind: String): ApnsConfig {
    val delivery = PushDeliveryConfig.forKind(kind)
    val aps =
        Aps
            .builder()
            .setSound("default")
            .setMutableContent(true)
            .setCategory(kind)
            .build()
    return ApnsConfig
        .builder()
        .setAps(aps)
        .putHeader("apns-priority", delivery.apnsPriority)
        .putHeader("apns-push-type", "alert")
        .putHeader("apns-collapse-id", delivery.collapseKey)
        .build()
}

/** Android delivery config: HIGH priority for high-urgency kinds (heads-up), NORMAL otherwise. */
internal fun androidConfigFor(kind: String): AndroidConfig {
    val delivery = PushDeliveryConfig.forKind(kind)
    return AndroidConfig
        .builder()
        .setPriority(if (delivery.highUrgency) AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL)
        .setCollapseKey(delivery.collapseKey)
        .build()
}
