package com.pynapli.app.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugQaReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!DebugQaFlags.isDebuggable(context)) {
            setResultCode(Activity.RESULT_CANCELED)
            setResultData("debug QA receiver is unavailable for non-debuggable builds")
            return
        }

        if (intent.action != DebugQaFlags.ACTION_SET_SWIPE_FAULT) {
            setResultCode(Activity.RESULT_CANCELED)
            setResultData("unsupported debug QA action")
            return
        }

        val failures =
            intent
                .getIntExtra(DebugQaFlags.EXTRA_FAIL_NEXT_SWIPE_ACTIONS, 0)
                .coerceIn(0, MAX_DEBUG_FAILURES)
        context
            .getSharedPreferences(DebugQaFlags.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(DebugQaFlags.KEY_FAIL_NEXT_SWIPE_ACTIONS, failures)
            .commit()

        setResultCode(Activity.RESULT_OK)
        setResultData("next swipe action failures set to $failures")
    }

    private companion object {
        const val MAX_DEBUG_FAILURES = 10
    }
}
