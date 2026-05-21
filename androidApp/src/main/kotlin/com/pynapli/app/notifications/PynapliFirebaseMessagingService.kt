package com.pynapli.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pynapli.app.R

class PynapliFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AndroidPushRegistrationStore(applicationContext).clear()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!canShowNotifications()) return

        val notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel(notificationManager)

        val launchIntent = PynapliNotificationDeepLinks.intentForNotification(
            context = this,
            data = message.data
        )
        val requestCode = (message.data["notificationId"] ?: message.messageId ?: message.sentTime.toString())
            .hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = notificationBuilder()
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.notification?.title ?: titleForKind(message.data["kind"]))
            .setContentText(message.notification?.body ?: bodyForKind(message.data["kind"]))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_SOCIAL)
            .build()

        notificationManager.notify(requestCode, notification)
    }

    private fun notificationBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_SOCIAL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_DEFAULT)
        }

    private fun ensureNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_SOCIAL)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_SOCIAL,
            "Activity and messages",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Social activity, matches, and message notifications"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun canShowNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun titleForKind(kind: String?): String =
        when (kind) {
            "message" -> "New message"
            "match" -> "New match"
            "flirt" -> "New activity"
            "profile_view" -> "New activity"
            "album_request" -> "Album request"
            "partner_chat_invite" -> "Chat invite"
            else -> "Pynapli"
        }

    private fun bodyForKind(kind: String?): String =
        when (kind) {
            "message" -> "Open messages"
            "match",
            "flirt",
            "profile_view",
            "album_request",
            "partner_chat_invite" -> "Open Activity"
            else -> "Open Pynapli"
        }

    companion object {
        private const val CHANNEL_SOCIAL = "pynapli_social"
    }
}
