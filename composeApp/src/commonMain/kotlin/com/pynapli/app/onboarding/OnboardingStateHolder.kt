package com.pynapli.app.onboarding

import androidx.compose.runtime.Immutable
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import com.pynapli.shared.settings.OnboardingProgressStore
import com.pynapli.shared.settings.PendingSignup
import com.pynapli.shared.settings.PendingSignupStore
import com.pynapli.shared.settings.maskEmailAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Drives the DOB-first signup stepper (ONBOARDING_AUTH_SPEC), aligned to the
 * founder's Grindr reference capture (2026-06-10): the birthday is typed into
 * DD/MM/YYYY fields (DD/MM/YYYY ordering founder-confirmed for the future Europe
 * launch), policies are reviewed on a dedicated step with read links and a single
 * Accept, and photo + display name share one skippable "Your profile" step.
 *
 * Steps: Dob -> (UnderageStop) -> Policies -> Account -> VerifyEmail -> Location -> Profile -> grid.
 * An underage visitor is stopped before any account data is collected. Account starts the
 * sign-up without creating anything; VerifyEmail takes the emailed 6-digit code, and only then
 * does the account (and the session) exist ("Membership privacy": the server answers the same
 * whether or not the address already has an account, so the copy here never says which).
 * Everything after VerifyEmail is skippable — "do it all later" lands on the grid in seconds.
 * Sign-in mode bypasses the stepper entirely.
 *
 * Resume (spec "app closed mid-signup"): before the code, [resumePendingSignupIfNeeded]
 * reopens the code step for the address that started, shown masked (the [PendingSignupStore]
 * keeps the address and the server's sign-up token, never the password: encrypted on Android,
 * for the tab's session only on the web; after 24 hours it starts over). After the code,
 * the device-local [OnboardingProgressStore] `completed` flag is false (pinned to the new
 * account); [resumeIfNeeded] re-enters at the Profile step on next launch until the user
 * finishes, which flips the flag back. Plain sign-ins never see the stepper: the flag defaults
 * true and only sign-up clears it.
 */
class OnboardingStateHolder(
    private val authStateHolder: AuthStateHolder,
    private val profileRepository: OnboardingProfileRepository,
    private val progressStore: OnboardingProgressStore,
    private val pendingSignupStore: PendingSignupStore,
    private val todayProvider: () -> LocalDate = ::systemToday,
    private val nowEpochMillis: () -> Long = ::systemNowEpochMillis,
) {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    // Per-process guard so a nudge impression counts one settings session, not one
    // per recomposition of the banner.
    private var photoNudgeCountedThisSession = false

    // Set once a sign-up's code is submitted (or a resume re-enters the stepper) in this process. The
    // add-a-photo nudge never arms in the session the stepper ran (spec: the first nudge lands a
    // session later). A wrong code also sets it; that only defers the nudge, never shows it early.
    private var signupRanThisSession = false

    // The started sign-up's token (random, from the server): needed with the code. Kept out of
    // the UI state; persisted only in the device-local progress store for resume.
    private var pendingSignupToken: String? = null

    // Failed code submissions for the current pending sign-up, counted here (the server answers every
    // failure the same way and says nothing about its own count). At the server's budget the code
    // step stops offering Verify and offers "Start again" instead.
    private var failedCodeSubmissions = 0

    fun startCreateAccount() {
        _state.update {
            it.copy(
                active = true,
                step = OnboardingStep.Dob,
                errorMessage = null,
            )
        }
    }

    /**
     * The stepper's "Sign in" links: straight to the sign-in form. A sign-up still waiting for its
     * code is dropped so the next launch doesn't reopen it. An address typed in this session is
     * carried over to the sign-in form; a resumed (masked) one never is.
     */
    suspend fun cancelToSignIn() {
        val current = _state.value
        val typedEmail = current.email.trim().takeIf { it.isNotEmpty() && !current.emailShownMasked }
        leaveStepper()
        authStateHolder.showSignIn(prefillEmail = typedEmail)
        clearPendingSignup()
    }

    /** Back from the first step: to the welcome landing, dropping any waiting sign-up. */
    suspend fun cancelToWelcome() {
        leaveStepper()
        authStateHolder.showWelcome()
        clearPendingSignup()
    }

    // State first, store second: the web router reads the step synchronously after a back press.
    private fun leaveStepper() {
        pendingSignupToken = null
        failedCodeSubmissions = 0
        _state.update {
            it.copy(
                active = false,
                step = OnboardingStep.Dob,
                email = if (it.emailShownMasked) "" else it.email,
                emailShownMasked = false,
                password = "",
                signupCode = "",
                signupAttemptsSpent = false,
                signupNotice = null,
                accountNotice = null,
                isSubmitting = false,
                errorMessage = null,
            )
        }
    }

    fun updateDobDay(value: String) {
        _state.update { it.copy(dobDay = value.digits(2), dobWheelUntouched = false, errorMessage = null) }
    }

    fun updateDobMonth(value: String) {
        _state.update { it.copy(dobMonth = value.digits(2), dobWheelUntouched = false, errorMessage = null) }
    }

    fun updateDobYear(value: String) {
        _state.update { it.copy(dobYear = value.digits(4), dobWheelUntouched = false, errorMessage = null) }
    }

    /**
     * The phone birthday wheel is showing. It needs a date to sit on: the one already entered if it
     * fits the wheel, else a neutral starting date ([neutralWheelDate], deliberately not "exactly 18
     * years ago"). A neutral start is not an answer: Next stays off until a wheel is moved
     * ([OnboardingUiState.dobComplete]), so nobody signs up on the default by accident.
     */
    fun showBirthdayWheel() {
        val today = todayProvider()
        val years = wheelYears(today)
        _state.update { current ->
            val withYears = current.copy(wheelFirstYear = years.first, wheelLastYear = years.last)
            if (wheelDateOf(current, years) != null) {
                withYears
            } else {
                val neutral = neutralWheelDate(today)
                withYears.copy(
                    dobDay = neutral.day.twoDigits(),
                    dobMonth = neutral.month.number.twoDigits(),
                    dobYear = neutral.year.toString(),
                    dobWheelUntouched = true,
                )
            }
        }
    }

    /**
     * A wheel moved. The day is clamped to the month (31 -> 30 for April, 29 -> 28 out of a leap
     * year); the date then goes through the same fields, so [confirmDob] validates it like a typed one.
     */
    fun selectWheelDate(
        day: Int,
        month: Int,
        year: Int,
    ) {
        val clampedMonth = month.coerceIn(1, 12)
        val clampedDay = clampWheelDay(year, clampedMonth, day)
        _state.update {
            it.copy(
                dobDay = clampedDay.twoDigits(),
                dobMonth = clampedMonth.twoDigits(),
                dobYear = year.toString(),
                dobWheelUntouched = false,
                errorMessage = null,
            )
        }
    }

    /** "Type it instead": an untouched neutral date is not the person's, so the fields start empty. */
    fun useTypedBirthday() {
        _state.update {
            if (it.dobWheelUntouched) {
                it.copy(dobDay = "", dobMonth = "", dobYear = "", dobWheelUntouched = false, errorMessage = null)
            } else {
                it
            }
        }
    }

    /** Validates the typed date, then gates 18+ before any account data is collected. */
    fun confirmDob() {
        val current = _state.value
        // The wheel's neutral start was never chosen: nothing to confirm yet (Next is off too).
        if (current.dobWheelUntouched) return
        val dob = parseDob(current.dobDay, current.dobMonth, current.dobYear, todayProvider())
        if (dob == null) {
            _state.update { it.copy(errorMessage = "Enter a valid date of birth.") }
            return
        }
        val nextStep =
            if (dob.isAtLeastYearsOld(MINIMUM_AGE_YEARS, todayProvider())) {
                OnboardingStep.Policies
            } else {
                OnboardingStep.UnderageStop
            }
        _state.update { it.copy(dob = dob, step = nextStep, errorMessage = null) }
    }

    fun backToDob() {
        _state.update { it.copy(step = OnboardingStep.Dob, errorMessage = null) }
    }

    fun backToPolicies() {
        _state.update { it.copy(step = OnboardingStep.Policies, errorMessage = null) }
    }

    /**
     * System back (Android) and browser back (web) inside the stepper (B6): one step back, never out
     * of the app mid-sign-up.
     *
     * Dob -> the welcome landing; UnderageStop and Policies -> Dob; Account -> Policies; VerifyEmail
     * -> Account, dropping the started sign-up like "Use a different email". Location and Profile
     * come after the code (the account exists), so back is consumed there and nothing changes.
     * Ignored while a request is in flight.
     *
     * Every state change happens before the first suspension point: the web router reads the
     * resulting step synchronously (see [com.pynapli.app.PynapliHistoryPlanner]).
     *
     * @return true when the step changed (or the stepper closed); false when back was consumed.
     */
    suspend fun stepBack(): Boolean {
        val current = _state.value
        if (!current.active || current.isSubmitting) return false
        when (current.step) {
            OnboardingStep.Dob -> cancelToWelcome()
            OnboardingStep.UnderageStop, OnboardingStep.Policies -> backToDob()
            OnboardingStep.Account -> backToPolicies()
            OnboardingStep.VerifyEmail -> useDifferentEmail()
            OnboardingStep.Location, OnboardingStep.Profile -> return false
        }
        return true
    }

    /**
     * The Policies step presents both documents with read links; one Accept tap
     * records both acceptances (Grindr-reference pattern) and the server contract
     * still receives them as separate explicit fields.
     */
    fun acceptPolicies() {
        _state.update {
            it.copy(
                acceptedTerms = true,
                acceptedPrivacy = true,
                step = OnboardingStep.Account,
                errorMessage = null,
            )
        }
    }

    fun updateEmail(value: String) {
        _state.update { it.copy(email = value, errorMessage = null) }
    }

    fun updatePassword(value: String) {
        _state.update { it.copy(password = value, errorMessage = null) }
    }

    fun updateDisplayName(value: String) {
        _state.update { it.copy(displayName = value.take(DISPLAY_NAME_MAX_LENGTH), errorMessage = null) }
    }

    /**
     * Starts the sign-up; on success moves to the code step. Nothing exists on the server yet
     * and no session is created; the password is dropped from memory here.
     */
    suspend fun submitAccount() {
        val current = _state.value
        if (current.isSubmitting) return
        val email = current.email.trim()
        val dob = current.dob
        if (dob == null) {
            _state.update { it.copy(step = OnboardingStep.Dob, errorMessage = "Enter your date of birth.") }
            return
        }
        if (!email.contains("@")) {
            _state.update { it.copy(errorMessage = "Enter a valid email address.") }
            return
        }
        if (current.password.length < MIN_PASSWORD_LENGTH) {
            _state.update { it.copy(errorMessage = "Password must be at least $MIN_PASSWORD_LENGTH characters.") }
            return
        }
        if (!current.acceptedTerms || !current.acceptedPrivacy) {
            _state.update { it.copy(errorMessage = "Accept the Terms and Privacy Policy to continue.") }
            return
        }

        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        val started =
            authStateHolder.signupWithDetails(
                email = email,
                password = current.password,
                birthdate = dob.toString(),
                // The DOB step already gated 18+; passing it explicitly keeps the
                // server contract's explicit-adult-confirmation semantics.
                confirmedAdult = true,
                acceptedTerms = current.acceptedTerms,
                acceptedPrivacy = current.acceptedPrivacy,
            )
        if (started != null) {
            pendingSignupToken = started.signupToken
            failedCodeSubmissions = 0
            savePendingSignup(email = email, signupToken = started.signupToken)
            _state.update {
                it.copy(
                    isSubmitting = false,
                    email = email,
                    emailShownMasked = false,
                    password = "",
                    signupCode = "",
                    signupNotice = null,
                    accountNotice = null,
                    signupAttemptsSpent = false,
                    step = OnboardingStep.VerifyEmail,
                )
            }
        } else {
            _state.update {
                it.copy(
                    isSubmitting = false,
                    errorMessage = authStateHolder.state.value.errorMessage,
                )
            }
        }
    }

    fun updateSignupCode(value: String) {
        _state.update { it.copy(signupCode = value.digits(SIGNUP_CODE_LENGTH), errorMessage = null) }
    }

    /** Checks the emailed code; on success the account and session exist and the skippable steps start. */
    suspend fun verifySignupCode() {
        val current = _state.value
        if (current.isSubmitting || current.signupAttemptsSpent) return
        val token = pendingSignupToken
        if (token == null) {
            restartSignup(SIGNUP_EXPIRED_MESSAGE)
            return
        }
        if (current.signupCode.length != SIGNUP_CODE_LENGTH) {
            _state.update { it.copy(errorMessage = "Enter the 6-digit code from the email.") }
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null, signupNotice = null) }
        // Before the session can exist (verifySignup publishes it): see evaluatePhotoNudge.
        signupRanThisSession = true
        val verified = authStateHolder.verifySignup(email = current.email, code = current.signupCode, signupToken = token)
        if (verified) {
            pendingSignupToken = null
            markOnboardingInProgress(
                authStateHolder.state.value.session
                    ?.userId,
            )
            _state.update {
                it.copy(
                    isSubmitting = false,
                    signupCode = "",
                    signupNotice = null,
                    step = OnboardingStep.Location,
                )
            }
        } else {
            failedCodeSubmissions++
            val spent = failedCodeSubmissions >= CODE_ATTEMPTS_PER_SIGNUP
            _state.update {
                it.copy(
                    isSubmitting = false,
                    signupCode = "",
                    signupAttemptsSpent = spent,
                    signupNotice = null,
                    errorMessage = if (spent) ATTEMPTS_SPENT_MESSAGE else authStateHolder.state.value.errorMessage,
                )
            }
        }
    }

    /**
     * "Start again": a new sign-up for the same address (the old one may be out of guesses, or may
     * never have been created if its email work was dropped). Back to email + password with the
     * address kept; the password is not stored, so the person types it again.
     */
    suspend fun startSignupAgain() {
        restartSignup(errorMessage = null)
        _state.update { it.copy(accountNotice = START_AGAIN_NOTICE) }
    }

    /** "Send a new code". The server answers the same whether or not one goes out, and so does this. */
    suspend fun resendSignupCode() {
        val current = _state.value
        if (current.isSubmitting || current.signupAttemptsSpent) return
        val token = pendingSignupToken
        if (token == null) {
            restartSignup(SIGNUP_EXPIRED_MESSAGE)
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null, signupNotice = null) }
        val sent = authStateHolder.resendSignupCode(email = current.email, signupToken = token)
        _state.update {
            if (sent) {
                it.copy(isSubmitting = false, signupCode = "", signupNotice = RESENT_NOTICE)
            } else {
                it.copy(isSubmitting = false, errorMessage = authStateHolder.state.value.errorMessage)
            }
        }
    }

    /** "Use a different email": drops the started sign-up and goes back to email + password. */
    suspend fun useDifferentEmail() {
        restartSignup(errorMessage = null)
    }

    /**
     * Pre-auth resume: an app killed between sign-up and the code reopens the code step for
     * that address. A sign-up older than the server keeps it restarts cleanly (nothing to do).
     */
    suspend fun resumePendingSignupIfNeeded() {
        // Signed out: the next session (even the same account's) is decided afresh.
        _state.update { it.copy(sessionSettledFor = null) }
        if (_state.value.active) return
        if (authStateHolder.state.value.session != null) return
        val pending = runCatchingCancellable { pendingSignupStore.load() }.getOrNull() ?: return
        if (nowEpochMillis() - pending.startedAtEpochMs >= PENDING_SIGNUP_LIFETIME_MILLIS) {
            clearPendingSignup()
            return
        }
        pendingSignupToken = pending.signupToken
        failedCodeSubmissions = 0
        _state.update {
            it.copy(
                active = true,
                step = OnboardingStep.VerifyEmail,
                email = pending.email,
                // Whoever opens the app next may not be the person who started: never the full address.
                emailShownMasked = true,
                password = "",
                signupCode = "",
                signupNotice = null,
                errorMessage = null,
            )
        }
    }

    /** Location step exits the same way whether the OS prompt was accepted or skipped. */
    fun continueFromLocation() {
        _state.update { it.copy(step = OnboardingStep.Profile, errorMessage = null) }
    }

    /**
     * Finishes the consolidated "Your profile" step: saves the display name when
     * one was typed (photo upload already ran through the media flow), then
     * releases the user to the grid. A blank name just skips — the spec keeps
     * every post-auth step optional.
     */
    suspend fun finishProfile() {
        val current = _state.value
        if (current.isSubmitting) return
        val name = current.displayName.trim()
        if (name.isEmpty()) {
            finish()
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        when (val result = profileRepository.updateDisplayName(name)) {
            is AppResult.Success -> {
                _state.update { it.copy(isSubmitting = false, displayName = name) }
                finish()
            }

            is AppResult.Failure ->
                _state.update {
                    it.copy(isSubmitting = false, errorMessage = result.error.message)
                }
        }
    }

    /** Releases the user to the grid and marks onboarding complete. */
    suspend fun finish() {
        markOnboardingCompleted()
        _state.update { it.copy(active = false, errorMessage = null) }
    }

    /**
     * Post-auth session entry point: re-enter an interrupted stepper, then decide
     * the add-a-photo nudge for this session. The nudge is deliberately skipped in
     * the same session the stepper runs — the user just saw (or will see) the
     * profile step, so the first nudge lands a session later (spec: bounded,
     * non-naggy).
     */
    suspend fun onSessionReady() {
        try {
            resumeIfNeeded()
            evaluatePhotoNudge()
        } finally {
            // Fail open: whatever happened above, the shell may now draw the main surfaces.
            _state.update { it.copy(sessionSettledFor = authStateHolder.state.value.session?.userId) }
        }
    }

    /**
     * The shell holds the main surfaces (no grid composed, so no grid load) until [onSessionReady]
     * has decided this session: otherwise a sign-up resume shows the grid for a frame or more before
     * the stepper takes the screen (B3). Signed-out, nothing is held.
     */
    fun holdsMainSurfaces(sessionUserId: String?): Boolean = _state.value.holdsMainSurfaces(sessionUserId)

    /**
     * Re-enters the consolidated Profile step after a mid-signup app close. No-op
     * for completed onboarding (the default) or unauthenticated sessions.
     */
    suspend fun resumeIfNeeded() {
        if (_state.value.active) return
        val sessionUserId =
            authStateHolder.state.value.session
                ?.userId ?: return
        val progress = progressStore.load()
        // Signed in some other way while a sign-up waited for its code: that sign-up is moot here.
        clearPendingSignup()
        if (progress.completed) return
        // The flag is device-local: only the account that started signup resumes.
        // Anyone else signing in on this device skips straight to the grid.
        if (progress.userId != sessionUserId) return

        // Take the screen first (B3): the stepper holds it while /me is in flight, so the grid never
        // shows in between. The saved name arrives after.
        signupRanThisSession = true
        _state.update {
            it.copy(
                active = true,
                step = OnboardingStep.Profile,
                errorMessage = null,
            )
        }
        val me = profileRepository.getMe()
        // Offline resume: no prefill, which is harmless (saving again later is idempotent).
        val savedName = (me as? AppResult.Success)?.value?.profile?.displayName.orEmpty()
        // Never overwrite what the person started typing while /me was in flight.
        _state.update { if (it.displayName.isEmpty()) it.copy(displayName = savedName) else it }
    }

    /**
     * Eligibility is decided once per session from persisted counters: never after a
     * dismiss, and at most [MAX_PHOTO_NUDGE_SESSIONS] impression sessions total. The
     * banner additionally requires a photoless profile at render time — that part is
     * live media state, not settings.
     */
    suspend fun evaluatePhotoNudge() {
        // The stepper ran (or is running) in this process: the first nudge lands a session later. An
        // in-memory flag, set before the session can exist, so a slow "in progress" store write can't
        // let this read the stale `completed = true` default and arm the banner.
        if (signupRanThisSession || _state.value.active) {
            _state.update { it.copy(photoNudgeEligible = false) }
            return
        }
        val progress = runCatchingCancellable { progressStore.load() }.getOrNull() ?: return
        val eligible =
            progress.completed &&
                !progress.photoNudgeDismissed &&
                progress.photoNudgeSessionsSeen < MAX_PHOTO_NUDGE_SESSIONS
        _state.update { it.copy(photoNudgeEligible = eligible) }
    }

    /** Counts an impression session the first time the banner actually renders. */
    suspend fun recordPhotoNudgeShown() {
        if (photoNudgeCountedThisSession) return
        photoNudgeCountedThisSession = true
        runCatchingCancellable {
            val progress = progressStore.load()
            progressStore.save(progress.copy(photoNudgeSessionsSeen = progress.photoNudgeSessionsSeen + 1))
        }
    }

    suspend fun dismissPhotoNudge() {
        _state.update { it.copy(photoNudgeEligible = false) }
        runCatchingCancellable { progressStore.save(progressStore.load().copy(photoNudgeDismissed = true)) }
    }

    private suspend fun markOnboardingInProgress(userId: String?) {
        runCatchingCancellable {
            progressStore.save(
                progressStore.load().copy(completed = false, userId = userId),
            )
        }
        clearPendingSignup()
    }

    private suspend fun savePendingSignup(
        email: String,
        signupToken: String,
    ) {
        runCatchingCancellable {
            pendingSignupStore.save(PendingSignup(email = email, signupToken = signupToken, startedAtEpochMs = nowEpochMillis()))
        }
    }

    private suspend fun clearPendingSignup() {
        runCatchingCancellable { pendingSignupStore.clear() }
    }

    // State first, store second: the web router reads the step synchronously after a back press.
    private suspend fun restartSignup(errorMessage: String?) {
        pendingSignupToken = null
        failedCodeSubmissions = 0
        _state.update {
            it.copy(
                step = OnboardingStep.Account,
                // A resumed address was only ever shown masked: never put it in full into the field
                // (whoever opened the app may not be the person who started).
                email = if (it.emailShownMasked) "" else it.email,
                emailShownMasked = false,
                password = "",
                signupCode = "",
                signupNotice = null,
                accountNotice = null,
                signupAttemptsSpent = false,
                isSubmitting = false,
                errorMessage = errorMessage,
            )
        }
        clearPendingSignup()
    }

    private suspend fun markOnboardingCompleted() {
        runCatchingCancellable {
            progressStore.save(
                progressStore.load().copy(completed = true, userId = null),
            )
        }
    }

    private fun String.digits(maxLength: Int): String = filter { it.isDigit() }.take(maxLength)

    companion object {
        const val MINIMUM_AGE_YEARS = 18
        const val MIN_PASSWORD_LENGTH = 12
        const val MAX_PHOTO_NUDGE_SESSIONS = 3
        const val DISPLAY_NAME_MAX_LENGTH = 15
        const val SIGNUP_CODE_LENGTH = 6

        /** The server keeps a started sign-up this long (EmailSignupService.PENDING_LIFETIME). */
        const val PENDING_SIGNUP_LIFETIME_MILLIS = 24L * 60 * 60 * 1_000
        const val RESENT_NOTICE = "If this address can be used, a new code is on its way. Use the newest one."
        const val SIGNUP_EXPIRED_MESSAGE = "That sign-up has expired. Enter your email again to get a new code."

        /** The server's guess budget per sign-up (EmailSignupService.MAX_ATTEMPTS). */
        const val CODE_ATTEMPTS_PER_SIGNUP = 5
        const val ATTEMPTS_SPENT_MESSAGE = "That's too many tries for this code. Start again to get a new one."
        const val START_AGAIN_NOTICE = "Enter your password again and we'll send a new code."

        /**
         * Strict calendar parse of the typed DD/MM/YYYY fields: real day for the
         * month (leap-aware), and a sane year window (no future dates, no >120y).
         */
        fun parseDob(
            day: String,
            month: String,
            year: String,
            today: LocalDate,
        ): LocalDate? {
            val dayValue = day.toIntOrNull() ?: return null
            val monthValue = month.toIntOrNull() ?: return null
            val yearValue = year.takeIf { it.length == 4 }?.toIntOrNull() ?: return null
            if (yearValue !in (today.year - MAX_AGE_YEARS)..today.year) return null
            if (monthValue !in 1..12) return null
            if (dayValue !in 1..daysInMonth(yearValue, monthValue)) return null
            val date = LocalDate(yearValue, monthValue, dayValue)
            if (date > today) return null
            return date
        }

        internal fun daysInMonth(
            year: Int,
            month: Int,
        ): Int =
            when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                else -> if (isLeapYear(year)) 29 else 28
            }

        internal fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        private const val MAX_AGE_YEARS = 120

        /** How far back the wheel's neutral start sits: an adult age, but not the 18-year boundary. */
        const val NEUTRAL_WHEEL_AGE_YEARS = 25

        /** The birthday wheel's years: the same window [parseDob] accepts, oldest first. */
        fun wheelYears(today: LocalDate): IntRange = (today.year - MAX_AGE_YEARS)..today.year

        /** Where the wheel starts: 1 January, [NEUTRAL_WHEEL_AGE_YEARS] years back. */
        fun neutralWheelDate(today: LocalDate): LocalDate = LocalDate(today.year - NEUTRAL_WHEEL_AGE_YEARS, 1, 1)

        /** Keeps a wheel day inside the month: the last day when the month (or a leap year) is shorter. */
        fun clampWheelDay(
            year: Int,
            month: Int,
            day: Int,
        ): Int = day.coerceIn(1, daysInMonth(year, month))

        /** The date the fields hold if the wheel can show it as is, else null. */
        internal fun wheelDateOf(
            state: OnboardingUiState,
            years: IntRange,
        ): LocalDate? {
            val year = state.dobYear.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in years } ?: return null
            val month = state.dobMonth.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            val day = state.dobDay.toIntOrNull()?.takeIf { it in 1..daysInMonth(year, month) } ?: return null
            return LocalDate(year, month, day)
        }

        private fun Int.twoDigits(): String = toString().padStart(2, '0')
    }
}

@OptIn(ExperimentalTime::class)
private fun systemToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

@OptIn(ExperimentalTime::class)
private fun systemNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Calendar-correct age gate: 18 exactly on the 18th birthday, not the day before. */
internal fun LocalDate.isAtLeastYearsOld(
    years: Int,
    today: LocalDate,
): Boolean {
    val birthdayThisYear = year + years
    return when {
        today.year > birthdayThisYear -> true
        today.year < birthdayThisYear -> false
        today.month.number > month.number -> true
        today.month.number < month.number -> false
        else -> today.day >= day
    }
}

enum class OnboardingStep {
    Dob,
    UnderageStop,
    Policies,
    Account,
    VerifyEmail,
    Location,
    Profile,
}

@Immutable
data class OnboardingUiState(
    val active: Boolean = false,
    val step: OnboardingStep = OnboardingStep.Dob,
    val dobDay: String = "",
    val dobMonth: String = "",
    val dobYear: String = "",
    val dob: LocalDate? = null,
    // The phone wheel is sitting on its neutral start and nobody has moved it yet: not an answer.
    val dobWheelUntouched: Boolean = false,
    // The wheel's year window, set when it is shown (the holder knows today; the screen doesn't).
    val wheelFirstYear: Int = 0,
    val wheelLastYear: Int = 0,
    val email: String = "",
    val password: String = "",
    // On a resumed code step the address is shown masked ([verifyEmailLabel]).
    val emailShownMasked: Boolean = false,
    val signupCode: String = "",
    // A neutral "new code is on its way" line on the code step; never says whether the address has an account.
    val signupNotice: String? = null,
    // The code step's guesses for this sign-up are used up: offer "Start again" instead of Verify.
    val signupAttemptsSpent: Boolean = false,
    // A neutral line on the Account step after "Start again".
    val accountNotice: String? = null,
    val acceptedTerms: Boolean = false,
    val acceptedPrivacy: Boolean = false,
    val displayName: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val photoNudgeEligible: Boolean = false,
    // The account [OnboardingStateHolder.onSessionReady] last decided (resume + nudge) for; see
    // [OnboardingStateHolder.holdsMainSurfaces].
    val sessionSettledFor: String? = null,
) {
    val dobComplete: Boolean
        get() = dobDay.isNotEmpty() && dobMonth.isNotEmpty() && dobYear.length == 4 && !dobWheelUntouched

    /** See [OnboardingStateHolder.holdsMainSurfaces]. */
    fun holdsMainSurfaces(sessionUserId: String?): Boolean = sessionUserId != null && !active && sessionSettledFor != sessionUserId

    /** The address the code step names: in full right after sign-up, masked after a resume. */
    val verifyEmailLabel: String
        get() = if (emailShownMasked) maskEmailAddress(email) else email
}

interface OnboardingProfileRepository {
    suspend fun updateDisplayName(displayName: String): AppResult<MeDto>

    suspend fun getMe(): AppResult<MeDto>
}

class ApiOnboardingProfileRepository(
    private val apiClient: PynapliApiClient,
) : OnboardingProfileRepository {
    override suspend fun updateDisplayName(displayName: String): AppResult<MeDto> = apiClient.updateProfile(UpdateProfileRequestDto(displayName = displayName))

    override suspend fun getMe(): AppResult<MeDto> = apiClient.getMe()
}
