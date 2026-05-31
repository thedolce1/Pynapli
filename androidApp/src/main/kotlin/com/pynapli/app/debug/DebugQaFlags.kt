package com.pynapli.app.debug

import android.content.Context
import android.content.pm.ApplicationInfo

object DebugQaFlags {
    const val ACTION_SET_SWIPE_FAULT = "com.pynapli.app.DEBUG_SET_SWIPE_FAULT"
    const val EXTRA_FAIL_NEXT_SWIPE_ACTIONS = "fail_next_swipe_actions"
    const val PREFS_NAME = "pynapli_debug_qa"
    const val KEY_FAIL_NEXT_SWIPE_ACTIONS = "fail_next_swipe_actions"

    fun isDebuggable(context: Context): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
