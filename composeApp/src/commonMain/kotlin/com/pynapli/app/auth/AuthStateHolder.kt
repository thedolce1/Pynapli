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
    private val appVersion: String
) {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    suspend fun restoreSession() {
        val session = authRepository.currentSession()
        _state.update { current ->
            current.copy(
                isCheckingSession = false,
                session = session
            )
        }
    }

    fun updateEmail(value: String) {
        _state.update { current -> current.copy(email = value, errorMessage = null) }
    }

    fun updatePassword(value: String) {
        _state.update { current -> current.copy(password = value, errorMessage = null) }
    }

    fun switchMode(mode: AuthMode) {
        _state.update { current ->
            current.copy(
                mode = mode,
                password = "",
                errorMessage = null
            )
        }
    }

    suspend fun submit() {
        val current = state.value
        if (current.isSubmitting) return

        submitCredentials(
            email = current.email,
            password = current.password,
            mode = current.mode
        )
    }

    suspend fun submitDevLogin(credentials: DevLoginCredentials) {
        if (state.value.isSubmitting) return

        submitCredentials(
            email = credentials.email,
            password = credentials.password,
            mode = AuthMode.SignIn
        )
    }

    private suspend fun submitCredentials(
        email: String,
        password: String,
        mode: AuthMode
    ) {
        val normalizedEmail = email.trim()
        if (!normalizedEmail.contains("@")) {
            _state.update { it.copy(errorMessage = "Enter a valid email address.") }
            return
        }
        if (password.length < 12) {
            _state.update { it.copy(errorMessage = "Password must be at least 12 characters.") }
            return
        }

        _state.update {
            it.copy(
                mode = mode,
                email = normalizedEmail,
                isSubmitting = true,
                errorMessage = null
            )
        }
        val result = when (mode) {
            AuthMode.SignIn -> authRepository.loginEmail(normalizedEmail, password, platform, appVersion)
            AuthMode.CreateAccount -> authRepository.signupEmail(normalizedEmail, password, platform, appVersion)
        }

        when (result) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        password = "",
                        isSubmitting = false,
                        errorMessage = null,
                        session = result.value
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun logout() {
        authRepository.logout()
        _state.update { current ->
            current.copy(
                session = null,
                password = "",
                errorMessage = null
            )
        }
    }
}
