package com.pynapli.app.perf

/**
 * Time-to-first-card markers (onboarding + first grid plan, Section 0). The QA scripts read them to
 * report how long a new member waits from "Create account" to the first Grid card, the baseline every
 * later section must not regress:
 * - Android: logcat tag `PynapliPerf`, message `<name> t=<elapsedRealtime ms>`
 *   (scripts/android-onboarding-qa.ps1).
 * - Web: `performance.mark('pynapli:<name>')` (scripts/web-qa/run.mjs).
 *
 * Each marker fires at most once per process, so the numbers describe the first journey only. The
 * names are fixed constants and carry nothing about the member (no ids, emails or locations).
 * Called from the UI thread only (Compose callbacks and side effects), so the set needs no lock.
 */
internal object PynapliPerfMarks {
    /** The landing screen's "Create account" tap that opens the sign-up stepper. */
    const val CREATE_ACCOUNT_TAP = "create_account_tap"

    /** The Account step's "Create account" tap that sends email + password. */
    const val ACCOUNT_SUBMIT = "account_submit"

    /** The first composition of the Grid with at least one card (cached or live). */
    const val FIRST_GRID_CARD = "first_grid_card"

    private val emitted = mutableSetOf<String>()

    fun markOnce(name: String) {
        if (emitted.add(name)) emitPlatformPerfMark(name)
    }
}

/** Writes one marker where the platform's QA tooling can read it; a no-op where there is none. */
internal expect fun emitPlatformPerfMark(name: String)
