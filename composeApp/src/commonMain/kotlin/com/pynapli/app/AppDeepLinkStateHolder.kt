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

    fun markHandled(commandId: Long) {
        _state.update { current -> if (current?.commandId == commandId) null else current }
    }

    companion object {
        const val TARGET_ACTIVITY = "activity"
        const val TARGET_MESSAGES = "messages"
    }
}
