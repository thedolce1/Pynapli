package com.pynapli.app

import com.pynapli.app.activity.ActivityTabUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppDeepLinkCommand(
    val commandId: Long,
    val target: String,
    val activityTab: ActivityTabUi? = null,
    val activityEventId: String? = null,
    val conversationId: String? = null,
)

class AppDeepLinkStateHolder {
    private var nextCommandId: Long = 0
    private val _state = MutableStateFlow<AppDeepLinkCommand?>(null)
    val state: StateFlow<AppDeepLinkCommand?> = _state.asStateFlow()

    fun openActivity(
        tab: ActivityTabUi? = null,
        eventId: String? = null,
    ) {
        _state.value =
            AppDeepLinkCommand(
                commandId = ++nextCommandId,
                target = TARGET_ACTIVITY,
                activityTab = tab,
                activityEventId = eventId,
            )
    }

    fun openMessages(conversationId: String? = null) {
        _state.value =
            AppDeepLinkCommand(
                commandId = ++nextCommandId,
                target = TARGET_MESSAGES,
                conversationId = conversationId,
            )
    }

    /** Simple tab/surface targets (browser URL routing, WEB_PWA_GAMEPLAN.md W1.2). */
    fun openGrid() = openSimpleTarget(TARGET_GRID)

    fun openSwipe() = openSimpleTarget(TARGET_SWIPE)

    fun openStore() = openSimpleTarget(TARGET_STORE)

    fun openProfile() = openSimpleTarget(TARGET_PROFILE)

    private fun openSimpleTarget(target: String) {
        _state.value =
            AppDeepLinkCommand(
                commandId = ++nextCommandId,
                target = target,
            )
    }

    fun markHandled(commandId: Long) {
        _state.update { current -> if (current?.commandId == commandId) null else current }
    }

    companion object {
        const val TARGET_ACTIVITY = "activity"
        const val TARGET_MESSAGES = "messages"
        const val TARGET_GRID = "grid"
        const val TARGET_SWIPE = "swipe"
        const val TARGET_STORE = "store"
        const val TARGET_PROFILE = "profile"
    }
}
