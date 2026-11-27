package com.pynapli.app.auth

import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.PasswordRules
import com.pynapli.shared.auth.SocialAgeGate
import com.pynapli.shared.auth.SocialAuthErrorCodes
import com.pynapli.shared.auth.SocialProvider
import com.pynapli.shared.auth.SocialProviderNonce
import com.pynapli.shared.auth.StoredSession
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
    // "Continue with Google / Apple" (ONBOARDING_AUTH_SPEC). Each shell passes the same instance it
    // gives PynapliCommonGraph; the default (nothing available) keeps tests and the buttons hidden.
    private val socialSignInProvider: SocialSignInProvider = UnavailableSocialSignInProvider,
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
                // A logout in progress revokes the session on the server first, so a request racing
                // it can end in a terminal refresh rejection. That is the member's own sign-out, not
                // a session that "ended": logout's welcome landing shows, not the notice.
                if (current.session == null || logoutInProgress) {
                    current
                } else {
                    // Straight to the sign-in form (not the welcome landing) so the reason is visible.
                    current.endedSession()
                }
            }
        }
    }

    /**
     * Another process changed the stored session (a second browser tab on the same localStorage; the
     * web shell calls this from the `storage` event). This tab keeps showing its own account's state,
     * so a stored session that is gone, or now belongs to someone else, ends this tab's session the
     * same way a terminal answer does: through signed-out, so every per-account reset runs, and onto
     * the sign-in form. The other account's session is never adopted. The same account's rotated
     * session (the other tab refreshed) is simply carried. A signed-out tab stays signed out.
     */
    fun onStoredSessionChanged(stored: StoredSession?) {
        _state.update { current ->
            val mine = current.session
            when {
                mine == null || logoutInProgress -> current
                stored == null || stored.userId != mine.userId -> current.endedSession()
                else -> current.copy(session = stored)
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

    // The address is cleared too: a session can die hours after its owner put the device down, and a
    // prefilled address would tell whoever picks it up who it was. Retyping it costs one autofill tap.
    private fun AuthUiState.endedSession(): AuthUiState =
        copy(
            mode = AuthMode.SignIn,
            showWelcome = false,
            session = null,
            email = "",
            password = "",
            isSubmitting = false,
            errorMessage = SESSION_ENDED_MESSAGE,
        )

    fun updateEmail(value: String) {
        _state.update { current -> current.copy(email = value, errorMessage = null, accountSuspended = false, appealSent = false, socialAppealProvider = null) }
    }

    fun updatePassword(value: String) {
        _state.update { current -> current.copy(password = value, errorMessage = null, accountSuspended = false, appealSent = false, socialAppealProvider = null) }
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
        current.socialAppealProvider?.let { provider ->
            submitSocialAccountAppeal(provider, reason)
            return
        }
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        when (val result = authRepository.submitAccountAppeal(current.email.trim(), current.password, reason, deviceIdOrNull)) {
            is AppResult.Success -> _state.update { it.copy(isSubmitting = false, appealSent = true) }
            is AppResult.Failure -> applyAppealFailure(result.error.code, result.error.message)
        }
    }

    /**
     * The appeal of an account that signs in with [provider] (no password to prove it): a fresh nonce
     * and a fresh provider sign-in, sent with the reason; no session is created.
     */
    private suspend fun submitSocialAccountAppeal(
        provider: SocialProvider,
        reason: String,
    ) {
        _state.update { it.copy(isSubmitting = true, socialInProgress = provider, errorMessage = null) }
        try {
            val nonce =
                when (val issued = authRepository.socialNonce(deviceIdOrNull)) {
                    is AppResult.Success -> issued.value.nonce

                    is AppResult.Failure -> {
                        _state.update { it.copy(errorMessage = issued.error.message) }
                        return
                    }
                }
            val credential =
                when (val result = socialSignInProvider.requestIdToken(provider, SocialProviderNonce.forProviderRequest(provider, nonce))) {
                    is SocialCredentialResult.Success -> result

                    SocialCredentialResult.Cancelled -> return

                    SocialCredentialResult.Unavailable -> {
                        _state.update { it.copy(errorMessage = "${provider.displayName} sign-in isn't available on this device.") }
                        return
                    }

                    is SocialCredentialResult.Error -> {
                        _state.update { it.copy(errorMessage = result.message) }
                        return
                    }
                }
            when (val result = authRepository.submitSocialAccountAppeal(provider, credential.idToken, nonce, reason)) {
                is AppResult.Success -> _state.update { it.copy(appealSent = true) }
                is AppResult.Failure -> applyAppealFailure(result.error.code, result.error.message)
            }
        } finally {
            _state.update { it.copy(isSubmitting = false, socialInProgress = null) }
        }
    }

    private fun applyAppealFailure(
        code: String,
        message: String,
    ) {
        _state.update {
            it.copy(
                isSubmitting = false,
                // An appeal already in review counts as sent.
                appealSent = code == "APPEAL_ALREADY_OPEN",
                errorMessage =
                    when (code) {
                        "APPEAL_ALREADY_OPEN" -> null

                        SocialAuthErrorCodes.ACCOUNT_NOT_RESTRICTED -> {
                            "This account isn't restricted any more. Try signing in again."
                        }

                        SocialAuthErrorCodes.ACCOUNT_NOT_FOUND -> {
                            "No Pynapli account signs in with that account. Try the one you used before."
                        }

                        SocialAuthErrorCodes.INVALID_TOKEN -> {
                            SOCIAL_NOT_CONFIRMED
                        }

                        else -> {
                            message
                        }
                    },
            )
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
            socialAppealProvider = null,
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

    /**
     * Loads which "Continue with ..." buttons to show (ONBOARDING_AUTH_SPEC): a provider shows only
     * when the platform can run it ([SocialSignInProvider.supports], which includes "this build has
     * a client id") and the server's config switches it on. Any failure hides them all; with no
     * provider on this platform the server is not even asked. Run whenever the signed-out surfaces
     * show.
     */
    suspend fun loadSocialSignInConfig() {
        val platformProviders = SocialProvider.entries.filter(socialSignInProvider::supports)
        if (platformProviders.isEmpty()) {
            _state.update { it.copy(socialProviders = emptyList()) }
            return
        }
        val enabled =
            when (val result = authRepository.socialSignInConfig()) {
                is AppResult.Success ->
                    platformProviders.filter { provider ->
                        when (provider) {
                            SocialProvider.Google -> result.value.google
                            SocialProvider.Apple -> result.value.apple
                        }
                    }

                is AppResult.Failure -> emptyList()
            }
        _state.update { it.copy(socialProviders = enabled) }
        if (enabled.isNotEmpty()) runCatchingCancellable { socialSignInProvider.warmUp(enabled.toSet()) }
    }

    /**
     * One "Continue with [provider]" attempt: a fresh nonce from the server (single use, so every
     * attempt fetches its own), the provider's sign-in UI for an ID token bound to it, then the
     * server. A known identity signs in here exactly like an email login. An unknown one needs the
     * birthday and policies first ([ageGate] null): that comes back as
     * [SocialSignInOutcome.NeedsAgeGate] and the stepper collects them, then calls this again with
     * [ageGate], which fetches a new nonce and a new token (the first nonce is spent).
     *
     * An address that already has an account is never linked on its own (a takeover risk, and it
     * would say who is a member): the sign-in form opens with a neutral notice instead. Tokens,
     * codes and nonces are never kept in state or logged.
     */
    suspend fun continueWithSocial(
        provider: SocialProvider,
        ageGate: SocialAgeGate? = null,
    ): SocialSignInOutcome {
        if (state.value.isSubmitting) return SocialSignInOutcome.Busy
        if (!socialSignInProvider.supports(provider)) return SocialSignInOutcome.ProviderUnavailable
        _state.update { it.copy(isSubmitting = true, socialInProgress = provider, errorMessage = null, accountSuspended = false, socialAppealProvider = null) }
        // Null while running, and still null if the caller's scope is cancelled mid-attempt.
        var outcome: SocialSignInOutcome? = null
        try {
            val settled = attemptSocial(provider, ageGate)
            outcome = settled
            return settled
        } finally {
            // Also when the caller's scope is cancelled while the provider UI is up: never stuck busy.
            _state.update { current ->
                val next = current.copy(isSubmitting = false, socialInProgress = null)
                when (val settled = outcome) {
                    is SocialSignInOutcome.Failed -> next.copy(errorMessage = settled.message)
                    else -> next
                }
            }
        }
    }

    private suspend fun attemptSocial(
        provider: SocialProvider,
        ageGate: SocialAgeGate?,
    ): SocialSignInOutcome {
        val nonce =
            when (val issued = authRepository.socialNonce(deviceIdOrNull)) {
                is AppResult.Success -> issued.value.nonce
                is AppResult.Failure -> return socialServerFailure(provider, issued.error.code, issued.error.message)
            }
        val credential =
            when (val result = socialSignInProvider.requestIdToken(provider, SocialProviderNonce.forProviderRequest(provider, nonce))) {
                is SocialCredentialResult.Success -> result

                SocialCredentialResult.Cancelled -> return SocialSignInOutcome.Cancelled

                SocialCredentialResult.Unavailable -> {
                    // This device can't run it after all: stop offering the button here.
                    _state.update { it.copy(socialProviders = it.socialProviders - provider) }
                    return SocialSignInOutcome.ProviderUnavailable
                }

                is SocialCredentialResult.Error -> return SocialSignInOutcome.Failed(result.message)
            }
        val result =
            authRepository.signInSocial(
                provider = provider,
                idToken = credential.idToken,
                nonce = nonce,
                authorizationCode = credential.authorizationCode,
                ageGate = ageGate,
                platform = platform,
                appVersion = appVersion,
                deviceId = deviceIdOrNull,
            )
        return when (result) {
            is AppResult.Success -> {
                _state.update { it.copy(password = "", errorMessage = null, session = result.value) }
                SocialSignInOutcome.SignedIn
            }

            is AppResult.Failure -> socialServerFailure(provider, result.error.code, result.error.message)
        }
    }

    private fun socialServerFailure(
        provider: SocialProvider,
        code: String,
        message: String,
    ): SocialSignInOutcome =
        when (code) {
            SocialAuthErrorCodes.NEEDS_AGE_GATE -> SocialSignInOutcome.NeedsAgeGate

            SocialAuthErrorCodes.EMAIL_HAS_ACCOUNT -> {
                // Straight to the sign-in form, the notice under it. Worded the same for every
                // address and provider; nothing is linked.
                _state.update { current ->
                    current.clearedForm(mode = AuthMode.SignIn, showWelcome = false).copy(errorMessage = emailHasAccountMessage(provider))
                }
                SocialSignInOutcome.EmailHasAccount
            }

            SocialAuthErrorCodes.INVALID_TOKEN -> SocialSignInOutcome.Failed(SOCIAL_NOT_CONFIRMED)

            SocialAuthErrorCodes.TEMPORARILY_UNAVAILABLE ->
                SocialSignInOutcome.Failed("${provider.displayName} sign-in isn't available right now. Try again in a few minutes.")

            SocialAuthErrorCodes.UNAVAILABLE -> {
                // Switched off on the server: hide every button.
                _state.update { it.copy(socialProviders = emptyList()) }
                SocialSignInOutcome.ProviderUnavailable
            }

            // A suspended account: the sign-in form with the message and the appeal form, which
            // proves the account through a fresh sign-in at this provider (no password needed).
            SocialAuthErrorCodes.ACCOUNT_NOT_ACTIVE -> {
                _state.update { current ->
                    current
                        .clearedForm(mode = AuthMode.SignIn, showWelcome = false)
                        .copy(accountSuspended = true, socialAppealProvider = provider)
                }
                SocialSignInOutcome.Failed(message)
            }

            else -> SocialSignInOutcome.Failed(message)
        }

    suspend fun logout() {
        logoutInProgress = true
        try {
            runCatchingCancellable { beforeLogout() }
            authRepository.logout()
            // Provider-side "signed in" memory (Google's account auto-select) goes too.
            runCatchingCancellable { socialSignInProvider.onSignedOut() }
            // Back to the welcome landing in sign-in mode, whatever the session was doing before. The
            // address goes too: on a shared device the next person must not see whose account was here
            // (that alone confirms the address is a member).
            _state.update { current -> current.clearedForm(mode = AuthMode.SignIn, showWelcome = true).copy(session = null, email = "") }
        } finally {
            logoutInProgress = false
        }
    }

    /** True from the start of [logout] until its state lands; see [observeSessionInvalidations]. */
    private var logoutInProgress = false

    companion object {
        /** The neutral "already has an account" notice (ONBOARDING_AUTH_SPEC). Never says more. */
        fun emailHasAccountMessage(provider: SocialProvider): String = "An account already uses this email. Sign in the way you usually do, then add ${provider.displayName} in Settings."

        const val SOCIAL_NOT_CONFIRMED = "We couldn't confirm that sign-in. Try again."
        const val SOCIAL_TRY_AGAIN = "Sign-in didn't work. Try again."

        private const val RESET_CODE_LENGTH = 6
        private const val MIN_APPEAL_REASON = 10
        private const val SESSION_ENDED_MESSAGE = "Your session ended. Please sign in again."
    }
}

/** How one [AuthStateHolder.continueWithSocial] attempt ended. */
sealed interface SocialSignInOutcome {
    /** The session is live (a known identity, or the sign-up just completed). */
    data object SignedIn : SocialSignInOutcome

    /** Unknown identity: collect Birthday + Policies, then call again with the age gate. */
    data object NeedsAgeGate : SocialSignInOutcome

    /** The address has an account: the sign-in form is showing with the neutral notice. */
    data object EmailHasAccount : SocialSignInOutcome

    /** The person closed the provider's UI. Nothing to show. */
    data object Cancelled : SocialSignInOutcome

    /** The provider can't run here (or the server switched it off); its button is gone. */
    data object ProviderUnavailable : SocialSignInOutcome

    /** Another request was in flight; nothing happened. */
    data object Busy : SocialSignInOutcome

    /** Anything else; [message] is also in [AuthUiState.errorMessage]. */
    data class Failed(
        val message: String,
    ) : SocialSignInOutcome
}
