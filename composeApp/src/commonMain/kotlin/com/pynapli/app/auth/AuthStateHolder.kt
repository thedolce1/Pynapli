package com.pynapli.app.auth

import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.PasswordRules
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.dto.APPEAL_REASON_MAX_LENGTH
import com.pynapli.shared.network.dto.EmailSignupStartedDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update

class AuthStateHolder(
    private val authRepository: AuthRepository,
    private val platform: String,
    private val appVersion: String,
    // Stable per-install identifier (account-farm signal, TRUST_AND_SAFETY_SPEC). Hashed
    // server-side before storage; blank when the platform graph supplies none.
    private val deviceId: String = "",
    private val beforeLogout: suspend () -> Unit = {},
    // Fires when the session is terminally invalidated mid-use (revoked / account no longer active).
    // Collected by [observeSessionInvalidations]; empty by default so tests and older call sites need
    // not supply it.
    private val sessionInvalidations: Flow<Unit> = emptyFlow(),
) {
    private val deviceIdOrNull: String? get() = deviceId.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /**
     * Long-running collector, launched once from the app effects, that drops the app back to the
     * sign-in screen when the session dies mid-use. The refresher has already cleared the stored
     * tokens by the time this fires, so here we only flip UI state; no network call (a logout call
     * would just 401 on the dead session).
     */
    suspend fun observeSessionInvalidations() {
        sessionInvalidations.collect {
            _state.update { current ->
                if (current.session == null) {
                    current
                } else {
                    // Straight to the sign-in form (not the welcome landing) so the reason is visible.
                    current.endedSession()
                }
            }
        }
    }

    /**
     * Cold start. A stored session goes straight in (the shell paints the cached Grid at once) and
     * `GET /v1/me` checks it in the background (plan G1). Only a terminal answer (revoked, refresh
     * rejected, account no longer active) signs out afterwards; a network error keeps the member in
     * with what is cached, and the next request retries. A stored session with no access token
     * cannot be sent anywhere, so it is checked before anything shows, as before.
     */
    suspend fun restoreSession() {
        val stored = authRepository.currentSession()
        if (stored == null || stored.accessToken.isBlank()) {
            val session = if (stored == null) null else authRepository.currentSessionValidated()
            _state.update { current -> current.copy(isCheckingSession = false, session = session) }
            return
        }
        _state.update { current -> current.copy(isCheckingSession = false, session = stored) }

        val validated = authRepository.currentSessionValidated()
        _state.update { current ->
            when {
                // Signed out, or signed in as someone else, while /me was on the wire: not ours to change.
                current.session != stored -> current
                // Dead (terminal answer), or the stored session now belongs to another account (signed
                // out and back in as someone else in another tab). Both end this tab's session: it is
                // showing the first account's cached state, so it must never pick up the other
                // account's tokens. Going through "signed out" lets every per-account reset run
                // (Grid, Favorites, location, overlays); handing over the new session directly
                // could skip them. Straight to the sign-in form, like observeSessionInvalidations,
                // so the reason is visible.
                validated == null || validated.userId != stored.userId -> current.endedSession()
                // Another tab rotated the same account's session meanwhile: carry the live one.
                else -> current.copy(session = validated)
            }
        }
    }

    private fun AuthUiState.endedSession(): AuthUiState =
        copy(
            mode = AuthMode.SignIn,
            showWelcome = false,
            session = null,
            password = "",
            isSubmitting = false,
            errorMessage = SESSION_ENDED_MESSAGE,
        )

    fun updateEmail(value: String) {
        _state.update { current -> current.copy(email = value, errorMessage = null, accountSuspended = false, appealSent = false) }
    }

    fun updatePassword(value: String) {
        _state.update { current -> current.copy(password = value, errorMessage = null, accountSuspended = false, appealSent = false) }
    }

    fun updateAppealReason(value: String) {
        _state.update { current -> current.copy(appealReason = value.take(APPEAL_REASON_MAX_LENGTH), errorMessage = null) }
    }

    suspend fun submitAccountAppeal() {
        val current = state.value
        if (current.isSubmitting || current.appealSent) return
        val reason = current.appealReason.trim()
        if (reason.length < MIN_APPEAL_REASON) {
            _state.update { it.copy(errorMessage = "Tell us a little about what happened.") }
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        when (val result = authRepository.submitAccountAppeal(current.email.trim(), current.password, reason, deviceIdOrNull)) {
            is AppResult.Success -> _state.update { it.copy(isSubmitting = false, appealSent = true) }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        // An appeal already in review counts as sent.
                        appealSent = result.error.code == "APPEAL_ALREADY_OPEN",
                        errorMessage = result.error.message.takeUnless { _ -> result.error.code == "APPEAL_ALREADY_OPEN" },
                    )
                }
        }
    }

    fun updateResetCode(value: String) {
        _state.update { current -> current.copy(resetCode = value.filter(Char::isDigit).take(RESET_CODE_LENGTH), errorMessage = null) }
    }

    /** Emails a 6-digit code (also used for "Send a new code"). */
    suspend fun requestPasswordReset() {
        val current = state.value
        if (current.isSubmitting) return
        val email = current.email.trim()
        if (!email.contains("@")) {
            _state.update { it.copy(errorMessage = "Enter the email you signed up with.") }
            return
        }
        _state.update { it.copy(email = email, isSubmitting = true, errorMessage = null, resetNotice = null) }
        when (val result = authRepository.requestPasswordReset(email, deviceIdOrNull)) {
            is AppResult.Success ->
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        resetCodeSent = true,
                        resetCode = "",
                        // Same words whether or not the address has an account.
                        resetNotice = "If $email has a Pynapli account, a 6-digit code is on its way. It works for 30 minutes.",
                    )
                }

            is AppResult.Failure -> _state.update { it.copy(isSubmitting = false, errorMessage = result.error.message) }
        }
    }

    suspend fun completePasswordReset() {
        val current = state.value
        if (current.isSubmitting) return
        if (current.resetCode.length != RESET_CODE_LENGTH) {
            _state.update { it.copy(errorMessage = "Enter the 6-digit code from the email.") }
            return
        }
        // A new password: the shared rule (length + common-password list), as the server checks it.
        PasswordRules.messageFor(current.password)?.let { message ->
            _state.update { it.copy(errorMessage = message) }
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        when (
            val result =
                authRepository.resetPasswordAndSignIn(
                    email = current.email.trim(),
                    code = current.resetCode,
                    newPassword = current.password,
                    platform = platform,
                    appVersion = appVersion,
                    deviceId = deviceIdOrNull,
                )
        ) {
            is AppResult.Success ->
                _state.update {
                    it.copy(
                        mode = AuthMode.SignIn,
                        password = "",
                        resetCode = "",
                        resetCodeSent = false,
                        resetNotice = null,
                        isSubmitting = false,
                        errorMessage = null,
                        session = result.value,
                    )
                }

            is AppResult.Failure -> _state.update { it.copy(isSubmitting = false, errorMessage = result.error.message) }
        }
    }

    fun switchMode(mode: AuthMode) {
        _state.update { current -> current.clearedForm(mode = mode, showWelcome = false) }
    }

    /**
     * Back to the welcome landing in sign-in mode: after sign-out, after leaving the sign-up stepper
     * from its first step, and on back from the sign-in form. Clears whatever a sign-up or an
     * earlier attempt left behind (an error, a half-typed password, a reset in progress).
     */
    fun showWelcome() {
        _state.update { current -> current.clearedForm(mode = AuthMode.SignIn, showWelcome = true) }
    }

    /**
     * Straight to the sign-in form, e.g. from the stepper's "Sign in" links. [prefillEmail] carries
     * an address the person typed in the stepper; callers never pass a masked (resumed) one.
     */
    fun showSignIn(prefillEmail: String? = null) {
        _state.update { current ->
            val cleared = current.clearedForm(mode = AuthMode.SignIn, showWelcome = false)
            if (prefillEmail != null) cleared.copy(email = prefillEmail) else cleared
        }
    }

    private fun AuthUiState.clearedForm(
        mode: AuthMode,
        showWelcome: Boolean,
    ): AuthUiState =
        copy(
            mode = mode,
            showWelcome = showWelcome,
            resetCodeSent = false,
            resetCode = "",
            resetNotice = null,
            password = "",
            isSubmitting = false,
            errorMessage = null,
            accountSuspended = false,
            appealReason = "",
            appealSent = false,
        )

    suspend fun submit() {
        val current = state.value
        if (current.isSubmitting) return
        if (current.mode == AuthMode.ResetPassword) {
            if (current.resetCodeSent) completePasswordReset() else requestPasswordReset()
            return
        }

        submitCredentials(email = current.email, password = current.password)
    }

    /**
     * Starts onboarding sign-up (ONBOARDING_AUTH_SPEC, "Membership privacy"): the stepper collects DOB
     * and Terms/Privacy as separate explicit acceptances, so this entry point takes them individually.
     * No session yet: the server emails a code (and answers the same whether or not the address
     * already has an account). Returns the started sign-up (keep its token for [verifySignup] and
     * [resendSignupCode]), or null with the error in [state].
     */
    suspend fun signupWithDetails(
        email: String,
        password: String,
        birthdate: String,
        confirmedAdult: Boolean,
        acceptedTerms: Boolean,
        acceptedPrivacy: Boolean,
    ): EmailSignupStartedDto? {
        if (state.value.isSubmitting) return null
        val normalizedEmail = email.trim()
        // Auth mode is left alone: the stepper owns the screen, and leaving it lands on sign-in.
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        val result =
            authRepository.signupEmail(
                email = normalizedEmail,
                password = password,
                birthdate = birthdate,
                confirmedAdult = confirmedAdult,
                acceptedTerms = acceptedTerms,
                acceptedPrivacy = acceptedPrivacy,
                platform = platform,
                appVersion = appVersion,
                deviceId = deviceIdOrNull,
            )
        return when (result) {
            is AppResult.Success -> {
                _state.update { it.copy(email = normalizedEmail, password = "", isSubmitting = false, errorMessage = null) }
                result.value
            }

            is AppResult.Failure -> {
                _state.update { it.copy(isSubmitting = false, errorMessage = result.error.message) }
                null
            }
        }
    }

    /**
     * Finishes sign-up with the emailed code; on success the account exists and the session lands in
     * [state] exactly like a sign-in. Returns true when the session is live.
     */
    suspend fun verifySignup(
        email: String,
        code: String,
        signupToken: String,
    ): Boolean {
        if (state.value.isSubmitting) return false
        val normalizedEmail = email.trim()
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        return when (
            val result =
                authRepository.verifyEmailSignup(
                    email = normalizedEmail,
                    code = code,
                    signupToken = signupToken,
                    platform = platform,
                    appVersion = appVersion,
                    deviceId = deviceIdOrNull,
                )
        ) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        email = normalizedEmail,
                        password = "",
                        isSubmitting = false,
                        errorMessage = null,
                        session = result.value,
                    )
                }
                true
            }

            is AppResult.Failure -> {
                _state.update { it.copy(isSubmitting = false, errorMessage = result.error.message) }
                false
            }
        }
    }

    /** Asks for a new sign-up code. The answer is the same whether or not one is sent. */
    suspend fun resendSignupCode(
        email: String,
        signupToken: String,
    ): Boolean {
        if (state.value.isSubmitting) return false
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        return when (val result = authRepository.resendEmailSignupCode(email.trim(), signupToken, deviceIdOrNull)) {
            is AppResult.Success -> {
                _state.update { it.copy(isSubmitting = false) }
                true
            }

            is AppResult.Failure -> {
                _state.update { it.copy(isSubmitting = false, errorMessage = result.error.message) }
                false
            }
        }
    }

    suspend fun submitDevLogin(credentials: DevLoginCredentials) {
        if (state.value.isSubmitting) return

        submitCredentials(email = credentials.email, password = credentials.password)
    }

    /** Email sign-in. Creating an account is the onboarding stepper's job, never this screen's. */
    private suspend fun submitCredentials(
        email: String,
        password: String,
    ) {
        val normalizedEmail = email.trim()
        if (!normalizedEmail.contains("@")) {
            _state.update { it.copy(errorMessage = "Enter a valid email address.") }
            return
        }
        // Sign-in checks the length only: a member whose password is on the common list still gets in.
        if (password.length < PasswordRules.MIN_LENGTH) {
            _state.update { it.copy(errorMessage = PasswordRules.TOO_SHORT_MESSAGE) }
            return
        }
        _state.update {
            it.copy(
                mode = AuthMode.SignIn,
                email = normalizedEmail,
                isSubmitting = true,
                errorMessage = null,
            )
        }
        val result = authRepository.loginEmail(normalizedEmail, password, platform, appVersion, deviceIdOrNull)

        when (result) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        password = "",
                        isSubmitting = false,
                        errorMessage = null,
                        session = result.value,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = result.error.message,
                        accountSuspended = result.error.code == "ACCOUNT_NOT_ACTIVE",
                    )
                }
            }
        }
    }

    suspend fun logout() {
        runCatchingCancellable { beforeLogout() }
        authRepository.logout()
        // Back to the welcome landing in sign-in mode, whatever the session was doing before.
        _state.update { current -> current.clearedForm(mode = AuthMode.SignIn, showWelcome = true).copy(session = null) }
    }

    private companion object {
        private const val RESET_CODE_LENGTH = 6
        private const val MIN_APPEAL_REASON = 10
        private const val SESSION_ENDED_MESSAGE = "Your session ended. Please sign in again."
    }
}
