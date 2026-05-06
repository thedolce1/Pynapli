package com.pynapli.server.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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
    private val firebaseApp: FirebaseApp
) : PushNotificationSender {
    private val logger = LoggerFactory.getLogger(FirebaseAdminPushNotificationSender::class.java)

    override suspend fun send(
        target: PushDeliveryTarget,
        notification: NotificationDeliveryRecord
    ): PushSendResult = withContext(Dispatchers.IO) {
        val message = Message.builder()
            .setToken(target.deviceToken)
            .setNotification(
                Notification.builder()
                    .setTitle(notification.title)
                    .setBody(notification.body)
                    .build()
            )
            .putAllData(notification.toDataPayload(target))
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
                    messageId
                )
                PushSendResult.accepted()
            },
            onFailure = { error ->
                if (error is FirebaseMessagingException) {
                    error.toPushSendResult()
                } else {
                    PushSendResult.retryableFailure(error.message ?: error::class.simpleName.orEmpty())
                }
            }
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

        private val terminalErrorCodes = setOf(
            "INVALID_ARGUMENT",
            "UNREGISTERED",
            "SENDER_ID_MISMATCH",
            "THIRD_PARTY_AUTH_ERROR"
        )

        fun fromConfig(config: ServerConfig): FirebaseAdminPushNotificationSender {
            val credentials = config.firebaseServiceAccountJson?.let { json ->
                ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)).use(GoogleCredentials::fromStream)
            } ?: config.firebaseServiceAccountPath?.let { path ->
                FileInputStream(path).use(GoogleCredentials::fromStream)
            } ?: throw IllegalStateException(
                "Firebase push provider requires PYNAPLI_FIREBASE_SERVICE_ACCOUNT_PATH or PYNAPLI_FIREBASE_SERVICE_ACCOUNT_JSON."
            )

            val app = FirebaseApp.getApps().firstOrNull { it.name == APP_NAME }
                ?: FirebaseApp.initializeApp(
                    FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .build(),
                    APP_NAME
                )
            return FirebaseAdminPushNotificationSender(app)
        }
    }
}
