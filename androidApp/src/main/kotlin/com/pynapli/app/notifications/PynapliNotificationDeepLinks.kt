package com.pynapli.app.notifications

import android.content.Context
import android.content.Intent
import com.pynapli.app.AppDeepLinkStateHolder
import com.pynapli.app.MainActivity
import com.pynapli.app.activity.ActivityTabUi
import org.json.JSONObject

object PynapliNotificationDeepLinks {
    const val EXTRA_TARGET = "pynapli.deep_link.target"
    const val EXTRA_ACTIVITY_TAB = "pynapli.deep_link.activity_tab"
    const val EXTRA_ACTIVITY_EVENT_ID = "pynapli.deep_link.activity_event_id"
    const val EXTRA_CONVERSATION_ID = "pynapli.deep_link.conversation_id"

    fun intentForNotification(context: Context, data: Map<String, String>): Intent {
        val payload = data["payload"].orEmpty().toJsonMap()
        val kind = data["kind"] ?: payload["kind"]
        val target = data["target"] ?: payload["target"] ?: targetForKind(kind)
        val activityTab = data["activityTab"] ?: payload["activityTab"] ?: activityTabForKind(kind)
        val activityEventId = data["activityEventId"] ?: payload["activityEventId"]
        val conversationId = data["conversationId"]
            ?: payload["conversationId"]
            ?: if (kind == "message") data["subjectId"] else null

        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET, target)
            activityTab?.let { putExtra(EXTRA_ACTIVITY_TAB, it) }
            activityEventId?.let { putExtra(EXTRA_ACTIVITY_EVENT_ID, it) }
            conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
        }
    }

    fun targetFromIntent(intent: Intent): String? =
        intent.getStringExtra(EXTRA_TARGET)

    fun activityTabFromIntent(intent: Intent): ActivityTabUi? =
        when (intent.getStringExtra(EXTRA_ACTIVITY_TAB)) {
            ActivityTabUi.Views.apiValue -> ActivityTabUi.Views
            ActivityTabUi.Flirts.apiValue -> ActivityTabUi.Flirts
            ActivityTabUi.Likes.apiValue -> ActivityTabUi.Likes
            ActivityTabUi.Matches.apiValue -> ActivityTabUi.Matches
            ActivityTabUi.Albums.apiValue -> ActivityTabUi.Albums
            ActivityTabUi.Invites.apiValue -> ActivityTabUi.Invites
            ActivityTabUi.All.apiValue -> ActivityTabUi.All
            else -> null
        }

    fun activityEventIdFromIntent(intent: Intent): String? =
        intent.getStringExtra(EXTRA_ACTIVITY_EVENT_ID)

    fun conversationIdFromIntent(intent: Intent): String? =
        intent.getStringExtra(EXTRA_CONVERSATION_ID)

    fun targetForKind(kind: String?): String =
        when (kind) {
            "message" -> AppDeepLinkStateHolder.TARGET_MESSAGES
            "match",
            "flirt",
            "profile_view",
            "album_request",
            "partner_chat_invite" -> AppDeepLinkStateHolder.TARGET_ACTIVITY
            else -> AppDeepLinkStateHolder.TARGET_ACTIVITY
        }

    fun activityTabForKind(kind: String?): String? =
        when (kind) {
            "profile_view" -> ActivityTabUi.Views.apiValue
            "flirt" -> ActivityTabUi.Flirts.apiValue
            "match" -> ActivityTabUi.Matches.apiValue
            "album_request" -> ActivityTabUi.Albums.apiValue
            "partner_chat_invite" -> ActivityTabUi.Invites.apiValue
            else -> null
        }

    private fun String.toJsonMap(): Map<String, String> =
        runCatching {
            val json = JSONObject(this)
            buildMap {
                json.keys().forEach { key ->
                    put(key, json.optString(key))
                }
            }
        }.getOrDefault(emptyMap())
}
