package com.pynapli.app.auth

import androidx.compose.runtime.Immutable
import com.pynapli.shared.auth.StoredSession

@Immutable
data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val birthdate: String = "",
    val acceptedAgeAndTerms: Boolean = false,
    val isCheckingSession: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val session: StoredSession? = null,
    // Forgot password: after "Send code", the screen asks for the emailed code and a new password.
    val resetCodeSent: Boolean = false,
    val resetCode: String = "",
    val resetNotice: String? = null,
) {
    val authenticated: Boolean
        get() = session != null
}

enum class AuthMode {
    SignIn,
    CreateAccount,
    ResetPassword,
}

@Immutable
data class DevLoginCredentials(
    val email: String,
    val password: String,
)
