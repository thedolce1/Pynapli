package com.pynapli.app.auth

import androidx.compose.runtime.Immutable
import com.pynapli.shared.auth.StoredSession

@Immutable
data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val isCheckingSession: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val session: StoredSession? = null
) {
    val authenticated: Boolean
        get() = session != null
}

enum class AuthMode {
    SignIn,
    CreateAccount
}

@Immutable
data class DevLoginCredentials(
    val email: String,
    val password: String
)
