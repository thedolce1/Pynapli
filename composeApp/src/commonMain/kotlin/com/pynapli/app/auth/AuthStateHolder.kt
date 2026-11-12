package com.pynapli.app.auth

import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
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
        _state.update { current -> current.copy(email = value, errorMessage = null) }
    }

    fun updatePassword(value: String) {
        _state.update { current -> current.copy(password = value, errorMessage = null) }
    }

    fun updateBirthdate(value: String) {
        _state.update { current -> current.copy(birthdate = value, errorMessage = null) }
    }

    fun updateAcceptedAgeAndTerms(value: Boolean) {
        _state.update { current -> current.copy(acceptedAgeAndTerms = value, errorMessage = null) }
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
     * Onboarding signup (ONBOARDING_AUTH_SPEC): the stepper collects DOB on its own
     * wheel and Terms/Privacy as separate explicit acceptances, so this entry point
     * takes them individually instead of the legacy single combined checkbox. On
     * success the session lands in [state] exactly like [submit].
     * Returns true when the account was created and the session is live.
     */
    suspend fun signupWithDetails(
        email: String,
        password: String,
        birthdate: String,
        confirmedAdult: Boolean,
        acceptedTerms: Boolean,
        acceptedPrivacy: Boolean,
    ): Boolean {
        if (state.value.isSubmitting) return false
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
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = result.error.message,
                    )
                }
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
                AuthMode.CreateAccount ->
                    authRepository.signupEmail(
                        email = normalizedEmail,
                        password = password,
                        birthdate = cleanBirthdate,
                        confirmedAdult = acceptedAgeAndTerms,
                        acceptedTerms = acceptedAgeAndTerms,
                        acceptedPrivacy = acceptedAgeAndTerms,
                        platform = platform,
                        appVersion = appVersion,
                        deviceId = deviceIdOrNull,
                    )
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
        private val birthdatePattern = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
