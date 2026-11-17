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
    const val EXTRA_OPEN_ACTOR_PROFILE = "pynapli.deep_link.open_actor_profile"

    fun intentForNotification(
        context: Context,
        data: Map<String, String>,
    ): Intent {
        val payload = data["payload"].orEmpty().toJsonMap()
        val kind = data["kind"] ?: payload["kind"]
        val target = data["target"] ?: payload["target"] ?: targetForKind(kind)
        val activityTab = data["activityTab"] ?: payload["activityTab"] ?: activityTabForKind(kind)
        val activityEventId = data["activityEventId"] ?: payload["activityEventId"]
        val conversationId =
            data["conversationId"]
                ?: payload["conversationId"]
                ?: if (kind == "message") data["subjectId"] else null

        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET, target)
            activityTab?.let { putExtra(EXTRA_ACTIVITY_TAB, it) }
            activityEventId?.let { putExtra(EXTRA_ACTIVITY_EVENT_ID, it) }
            conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
            // "X shared their private album" opens their profile with the album ready to view.
            if (kind == "album_granted") (data["ownerUserId"] ?: payload["ownerUserId"])?.let { putExtra(EXTRA_OPEN_ACTOR_PROFILE, it) }
        }
    }

    fun actorProfileToOpen(intent: Intent): String? = intent.getStringExtra(EXTRA_OPEN_ACTOR_PROFILE)

    fun targetFromIntent(intent: Intent): String? = intent.getStringExtra(EXTRA_TARGET)

    fun activityTabFromIntent(intent: Intent): ActivityTabUi? =
        when (intent.getStringExtra(EXTRA_ACTIVITY_TAB)) {
            ActivityTabUi.Views.apiValue -> ActivityTabUi.Views
            ActivityTabUi.Flirts.apiValue -> ActivityTabUi.Flirts
            ActivityTabUi.Matches.apiValue -> ActivityTabUi.Matches
            ActivityTabUi.Requests.apiValue -> ActivityTabUi.Requests
            else -> null
        }

    fun activityEventIdFromIntent(intent: Intent): String? = intent.getStringExtra(EXTRA_ACTIVITY_EVENT_ID)

    fun conversationIdFromIntent(intent: Intent): String? = intent.getStringExtra(EXTRA_CONVERSATION_ID)

    fun targetForKind(kind: String?): String =
        when (kind) {
            // Matches now open in Messages (new-matches rail), not Activity.
            "message",
            "match",
            -> AppDeepLinkStateHolder.TARGET_MESSAGES
            "flirt",
            "profile_view",
            "album_request",
            "partner_chat_invite",
            -> AppDeepLinkStateHolder.TARGET_ACTIVITY
            // Pushes queued before the server sent an explicit target still land in the right place.
            "tonight_invite",
            "tonight_reminder",
            "tonight_accepted",
            "tonight_declined",
            "tonight_ended",
            -> AppDeepLinkStateHolder.TARGET_TONIGHT
            "psync_invite" -> AppDeepLinkStateHolder.TARGET_PSYNC
            // Link invites are answered in the linked-partner panel; they never appear in Activity.
            "profile_link_invite",
            "profile_link_accepted",
            -> AppDeepLinkStateHolder.TARGET_PARTNER
            else -> AppDeepLinkStateHolder.TARGET_ACTIVITY
        }

    fun activityTabForKind(kind: String?): String? =
        when (kind) {
            "profile_view" -> ActivityTabUi.Views.apiValue
            "flirt" -> ActivityTabUi.Flirts.apiValue
            "album_request",
            "album_granted",
            "partner_chat_invite",
            "psync_invite",
            -> ActivityTabUi.Requests.apiValue
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
