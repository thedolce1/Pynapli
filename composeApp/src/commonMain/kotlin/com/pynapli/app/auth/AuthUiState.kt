package com.pynapli.app.auth

import androidx.compose.runtime.Immutable
import com.pynapli.shared.auth.SocialProvider
import com.pynapli.shared.auth.StoredSession

@Immutable
data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    // The welcome landing (value line, Create account, Sign in) covers the sign-in form until the
    // person picks Sign in. Creating an account always goes through the onboarding stepper; this
    // screen has no create-account form of its own.
    val showWelcome: Boolean = true,
    val email: String = "",
    val password: String = "",
    val isCheckingSession: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val session: StoredSession? = null,
    // Forgot password: after "Send code", the screen asks for the emailed code and a new password.
    val resetCodeSent: Boolean = false,
    val resetCode: String = "",
    val resetNotice: String? = null,
    // Sign-in said the account is suspended or banned: the screen offers an appeal with the
    // email and password just typed (a suspended account can't get a session to appeal from).
    val accountSuspended: Boolean = false,
    val appealReason: String = "",
    val appealSent: Boolean = false,
    // "Continue with Google / Apple" buttons to show: the server switched the provider on AND this
    // platform can run it. Empty (every button hidden) until the config answers, and on any failure.
    val socialProviders: List<SocialProvider> = emptyList(),
    // The provider whose attempt is in flight (its button shows a spinner).
    val socialInProgress: SocialProvider? = null,
    // A Google / Apple sign-in said the account is suspended: the appeal form proves the account
    // with a fresh sign-in at that provider instead of the email and password.
    val socialAppealProvider: SocialProvider? = null,
) {
    val authenticated: Boolean
        get() = session != null
}

enum class AuthMode {
    SignIn,
    ResetPassword,
}

@Immutable
data class DevLoginCredentials(
    val email: String,
    val password: String,
)
