package com.pynapli.app.perf

/** The dev-only desktop shell has no QA timing run, so the markers go nowhere. */
internal actual fun emitPlatformPerfMark(name: String) = Unit
