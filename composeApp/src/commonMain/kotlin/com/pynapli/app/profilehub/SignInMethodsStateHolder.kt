package com.pynapli.app.profilehub

import androidx.compose.runtime.Immutable
import com.pynapli.app.auth.SocialCredentialResult
import com.pynapli.app.auth.SocialMarkArtwork
import com.pynapli.app.auth.SocialSignInProvider
import com.pynapli.app.auth.visibleSocialProviders
import com.pynapli.shared.auth.SocialAuthErrorCodes
import com.pynapli.shared.auth.SocialIdentityRepository
import com.pynapli.shared.auth.SocialProvider
import com.pynapli.shared.auth.SocialProviderNonce
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.LinkedIdentitiesDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Settings > Sign-in methods (ONBOARDING_AUTH_SPEC, "Continue with Google / Apple"): the account's
 * password and linked providers, adding Google or Apple, and removing one that is not the last way
 * in. This is the only way a provider joins an existing account: nothing is ever linked by email.
 *
 * The server decides everything (whether a provider is on, whether that Google account is free,
 * whether a method may go); this holds what it said, refetches after every change (HTTP is
 * canonical) and words its refusals. No token, code or nonce is kept.
 */
class SignInMethodsStateHolder(
    private val repository: SocialIdentityRepository,
    private val socialSignInProvider: SocialSignInProvider,
) {
    private val _state = MutableStateFlow(SignInMethodsUiState())
    val state: StateFlow<SignInMethodsUiState> = _state.asStateFlow()

    /**
     * Which providers can be offered here (server flag AND platform; the server is not asked when the
     * platform has none), then the account's methods. The methods are always fetched, whatever the
     * providers: they also say whether the account has a password, which decides whether Settings
     * offers "Change password" at all. A failure here is quiet (the hub just shows less).
     */
    suspend fun load() {
        _state.update { it.copy(isLoading = true) }
        val platformProviders = SocialProvider.entries.filter(socialSignInProvider::supports)
        val available =
            if (platformProviders.isEmpty()) {
                emptyList()
            } else {
                when (val config = repository.config()) {
                    is AppResult.Success ->
                        platformProviders.filter { provider ->
                            when (provider) {
                                SocialProvider.Google -> config.value.google
                                SocialProvider.Apple -> config.value.apple
                            }
                        }

                    is AppResult.Failure -> emptyList()
                }
            }
        if (available.isNotEmpty()) runCatchingWarmUp(available)
        refreshMethods(available, reportFailure = false)
    }

    private suspend fun refreshMethods(
        available: List<SocialProvider> = _state.value.available,
        reportFailure: Boolean = true,
    ) {
        when (val methods = repository.signInMethods()) {
            is AppResult.Success -> applyMethods(methods.value, available)

            // The password flag stays as last known (null, so no Change password, if never known).
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        available = available,
                        isLoading = false,
                        loaded = true,
                        errorMessage = if (reportFailure) methods.error.message else it.errorMessage,
                    )
                }
        }
    }

    // The password method comes as provider "email" and is shown from [hasPassword], so only Google /
    // Apple become rows here (an unknown provider is ignored).
    private fun applyMethods(
        methods: LinkedIdentitiesDto,
        available: List<SocialProvider> = _state.value.available,
    ) {
        _state.update {
            it.copy(
                available = available,
                hasPassword = methods.hasPassword,
                linked =
                    methods.identities.mapNotNull { identity ->
                        SocialProvider.fromWire(identity.provider)?.let { provider -> LinkedSignInMethod(provider, identity.emailHint) }
                    },
                isLoading = false,
                loaded = true,
            )
        }
    }

    /** Adds [provider]: the provider's own sign-in for a fresh nonce, then the server links it. */
    suspend fun link(provider: SocialProvider) {
        if (_state.value.busyProvider != null) return
        _state.update { it.copy(busyProvider = provider, errorMessage = null, feedbackMessage = null) }
        try {
            val nonce =
                when (val issued = repository.nonce()) {
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
            when (val linked = repository.link(provider, credential.idToken, nonce, credential.authorizationCode)) {
                // The answer is the updated list: no refetch needed.
                is AppResult.Success -> {
                    applyMethods(linked.value)
                    _state.update { it.copy(feedbackMessage = "${provider.displayName} added. You can sign in with it now.") }
                }

                is AppResult.Failure ->
                    _state.update {
                        it.copy(
                            errorMessage =
                                when (linked.error.code) {
                                    SocialAuthErrorCodes.IDENTITY_IN_USE ->
                                        "That ${provider.displayName} account is already used to sign in to another Pynapli account."

                                    SocialAuthErrorCodes.PROVIDER_ALREADY_LINKED ->
                                        "This account already has a ${provider.displayName} account added. Remove it first to add a different one."

                                    // 400 here (never 401, so no session refresh resends the spent nonce).
                                    SocialAuthErrorCodes.INVALID_TOKEN -> "We couldn't confirm that sign-in. Try again."

                                    SocialAuthErrorCodes.UNAVAILABLE -> "${provider.displayName} sign-in isn't available right now."

                                    else -> linked.error.message
                                },
                        )
                    }
            }
        } finally {
            _state.update { it.copy(busyProvider = null) }
        }
    }

    /** Removes [provider]. The server refuses the last way in (`LAST_SIGN_IN_METHOD`). */
    suspend fun unlink(provider: SocialProvider) {
        if (_state.value.busyProvider != null) return
        _state.update { it.copy(busyProvider = provider, errorMessage = null, feedbackMessage = null) }
        try {
            when (val result = repository.unlink(provider)) {
                is AppResult.Success -> {
                    _state.update { it.copy(feedbackMessage = "${provider.displayName} removed.") }
                    refreshMethods()
                }

                // Already gone (removed on another device): just show the list as it is now.
                is AppResult.Failure ->
                    if (result.error.code == SocialAuthErrorCodes.IDENTITY_NOT_LINKED) {
                        refreshMethods()
                    } else {
                        _state.update {
                            it.copy(
                                errorMessage =
                                    if (result.error.code == SocialAuthErrorCodes.LAST_SIGN_IN_METHOD) {
                                        LAST_METHOD_MESSAGE
                                    } else {
                                        result.error.message
                                    },
                            )
                        }
                    }
            }
        } finally {
            _state.update { it.copy(busyProvider = null) }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(errorMessage = null, feedbackMessage = null) }
    }

    /** Signed out: the next account starts from nothing. */
    fun reset() {
        _state.value = SignInMethodsUiState()
    }

    private fun runCatchingWarmUp(available: List<SocialProvider>) {
        runCatching { socialSignInProvider.warmUp(available.toSet()) }
    }

    companion object {
        const val LAST_METHOD_MESSAGE = "This is your only way to sign in. Add another sign-in method before removing it."
    }
}

@Immutable
data class LinkedSignInMethod(
    val provider: SocialProvider,
    // The server's masked address for this method (j•••@gmail.com); null when it has none.
    val emailHint: String? = null,
)

@Immutable
data class SignInMethodsUiState(
    // Providers this platform can link right now (server on AND platform able). Empty hides the row.
    val available: List<SocialProvider> = emptyList(),
    // From GET /v1/auth/identities (non-null there); null until it has answered. Only a known true offers
    // "Change password" (an account that signs in with Google / Apple alone has nothing to change).
    val hasPassword: Boolean? = null,
    val linked: List<LinkedSignInMethod> = emptyList(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val busyProvider: SocialProvider? = null,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
) {
    /** Providers with an Add button: [available] minus any whose official artwork is not in. */
    internal fun offered(artwork: SocialMarkArtwork = SocialMarkArtwork.Current): List<SocialProvider> = visibleSocialProviders(available, artwork)

    /** The Settings row shows only when there is something to manage: a provider to add, or one to remove. */
    internal fun visibleWith(artwork: SocialMarkArtwork): Boolean = offered(artwork).isNotEmpty() || linked.isNotEmpty()

    val visible: Boolean
        get() = visibleWith(SocialMarkArtwork.Current)

    fun isLinked(provider: SocialProvider): Boolean = linked.any { it.provider == provider }

    /** Settings shows "Change password" only when the server said this account has one. */
    val showChangePassword: Boolean
        get() = hasPassword == true

    /** One line for the Settings row, e.g. "Password, Google". */
    val summary: String
        get() =
            buildList {
                if (hasPassword == true) add("Password")
                linked.forEach { add(it.provider.displayName) }
            }.joinToString(", ").ifEmpty { "Manage" }
}
