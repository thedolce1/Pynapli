package com.pynapli.app.auth

import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AuthStateHolder(
    private val authRepository: AuthRepository,
    private val platform: String,
    private val appVersion: String,
    private val beforeLogout: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

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

    fun switchMode(mode: AuthMode) {
        _state.update { current ->
            current.copy(
                mode = mode,
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

        submitCredentials(
            email = current.email,
            password = current.password,
            birthdate = current.birthdate,
            acceptedAgeAndTerms = current.acceptedAgeAndTerms,
            mode = current.mode,
        )
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
                AuthMode.SignIn -> authRepository.loginEmail(normalizedEmail, password, platform, appVersion)
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
        runCatching { beforeLogout() }
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
        private val birthdatePattern = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
