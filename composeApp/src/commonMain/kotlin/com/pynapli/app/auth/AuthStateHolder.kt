package com.pynapli.app.auth

import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.core.result.AppError
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
                    current.copy(
                        session = null,
                        password = "",
                        errorMessage = "Your session ended. Please sign in again.",
                    )
                }
            }
        }
    }

    suspend fun restoreSession() {
        val session = authRepository.currentSessionValidated()
        _state.update { current ->
            current.copy(
                isCheckingSession = false,
                session = session,
            )
        }
    }

    fun updateEmail(value: String) {
        _state.update { current -> current.copy(email = value, errorMessage = null, accountSuspended = false, appealSent = false) }
    }

    fun updatePassword(value: String) {
        _state.update { current -> current.copy(password = value, errorMessage = null, accountSuspended = false, appealSent = false) }
    }

    fun updateBirthdate(value: String) {
        _state.update { current -> current.copy(birthdate = value, errorMessage = null) }
    }

    fun updateAcceptedAgeAndTerms(value: Boolean) {
        _state.update { current -> current.copy(acceptedAgeAndTerms = value, errorMessage = null) }
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
        if (current.password.length < 12) {
            _state.update { it.copy(errorMessage = "Password must be at least 12 characters.") }
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
        _state.update { current ->
            current.copy(
                mode = mode,
                resetCodeSent = false,
                resetCode = "",
                resetNotice = null,
                password = "",
                birthdate = if (mode == AuthMode.SignIn) "" else current.birthdate,
                acceptedAgeAndTerms = if (mode == AuthMode.SignIn) false else current.acceptedAgeAndTerms,
                errorMessage = null,
            )
        }
    }

    suspend fun submit() {
        val current = state.value
        if (current.isSubmitting) return
        if (current.mode == AuthMode.ResetPassword) {
            if (current.resetCodeSent) completePasswordReset() else requestPasswordReset()
            return
        }

        submitCredentials(
            email = current.email,
            password = current.password,
            birthdate = current.birthdate,
            acceptedAgeAndTerms = current.acceptedAgeAndTerms,
            mode = current.mode,
        )
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
        _state.update { it.copy(isSubmitting = true, errorMessage = null, mode = AuthMode.CreateAccount) }
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

        submitCredentials(
            email = credentials.email,
            password = credentials.password,
            birthdate = "",
            acceptedAgeAndTerms = false,
            mode = AuthMode.SignIn,
        )
    }

    private suspend fun submitCredentials(
        email: String,
        password: String,
        birthdate: String,
        acceptedAgeAndTerms: Boolean,
        mode: AuthMode,
    ) {
        val normalizedEmail = email.trim()
        val cleanBirthdate = birthdate.trim()
        if (!normalizedEmail.contains("@")) {
            _state.update { it.copy(errorMessage = "Enter a valid email address.") }
            return
        }
        if (password.length < 12) {
            _state.update { it.copy(errorMessage = "Password must be at least 12 characters.") }
            return
        }
        if (mode == AuthMode.CreateAccount && !birthdatePattern.matches(cleanBirthdate)) {
            _state.update { it.copy(errorMessage = "Birthdate must use YYYY-MM-DD.") }
            return
        }
        if (mode == AuthMode.CreateAccount && !acceptedAgeAndTerms) {
            _state.update { it.copy(errorMessage = "Confirm you are 18+ and accept the terms.") }
            return
        }

        _state.update {
            it.copy(
                mode = mode,
                email = normalizedEmail,
                birthdate = cleanBirthdate.takeIf { _ -> mode == AuthMode.CreateAccount } ?: it.birthdate,
                isSubmitting = true,
                errorMessage = null,
            )
        }
        val result =
            when (mode) {
                AuthMode.SignIn, AuthMode.ResetPassword -> authRepository.loginEmail(normalizedEmail, password, platform, appVersion, deviceIdOrNull)
                // The legacy inline form has no code step, and every shell builds the onboarding
                // stepper (which has one), so this form never starts a sign-up itself any more.
                AuthMode.CreateAccount -> AppResult.Failure(AppError(code = "SIGNUP_NEEDS_STEPPER", message = LEGACY_SIGNUP_MESSAGE))
            }

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
                        accountSuspended = mode == AuthMode.SignIn && result.error.code == "ACCOUNT_NOT_ACTIVE",
                    )
                }
            }
        }
    }

    suspend fun logout() {
        runCatchingCancellable { beforeLogout() }
        authRepository.logout()
        _state.update { current ->
            current.copy(
                session = null,
                password = "",
                birthdate = "",
                acceptedAgeAndTerms = false,
                errorMessage = null,
            )
        }
    }

    private companion object {
        private const val RESET_CODE_LENGTH = 6
        private const val MIN_APPEAL_REASON = 10
        private const val LEGACY_SIGNUP_MESSAGE = "Creating an account isn't available from this screen. Restart the app and choose Create account."
        private val birthdatePattern = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
