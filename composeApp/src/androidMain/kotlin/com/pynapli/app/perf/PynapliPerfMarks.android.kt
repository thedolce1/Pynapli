package com.pynapli.app.perf

import android.os.SystemClock
import android.util.Log

/** `adb logcat -s PynapliPerf` shows `<name> t=<ms since boot>`; the QA script subtracts two of them. */
internal actual fun emitPlatformPerfMark(name: String) {
    Log.i("PynapliPerf", "$name t=${SystemClock.elapsedRealtime()}")
}
