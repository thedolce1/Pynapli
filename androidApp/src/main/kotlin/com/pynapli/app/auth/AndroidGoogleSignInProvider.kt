package com.pynapli.app.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.pynapli.shared.auth.SocialProvider
import com.pynapli.shared.core.result.runCatchingCancellable
import java.lang.ref.WeakReference

/**
 * "Continue with Google" on Android through Credential Manager (developer.android.com/identity/
 * sign-in/credential-manager-siwg-implementation).
 *
 * Only the explicit button flow ([GetSignInWithGoogleOption]) is used: the person tapped "Continue
 * with Google", so they get Google's account picker, including accounts that need re-authentication
 * and "add an account". The bottom-sheet flow ([com.google.android.libraries.identity.googleid.
 * GetGoogleIdOption], with automatic sign-in) is deliberately not used: the app never signs anyone
 * in on its own (a shared phone must not open someone's account by itself).
 *
 * [serverClientId] is the **web** OAuth client id of the Google Cloud project (that is the audience
 * the server checks); it comes from build config (`pynapli.android.googleServerClientId`), never
 * from source. Blank means the button stays hidden. Apple on Android would need a web flow and is
 * out of scope: [supports] is false for Apple.
 *
 * Credential Manager needs an Activity context to show its UI, so MainActivity attaches itself
 * (the same pattern as Play Billing). Nothing here logs a token.
 */
class AndroidGoogleSignInProvider(
    context: Context,
    private val serverClientId: String,
) : SocialSignInProvider {
    private val appContext = context.applicationContext
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(appContext) }

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    override fun supports(provider: SocialProvider): Boolean = provider == SocialProvider.Google && serverClientId.isNotBlank()

    override suspend fun requestIdToken(
        provider: SocialProvider,
        nonce: String,
    ): SocialCredentialResult {
        if (!supports(provider)) return SocialCredentialResult.Unavailable
        val activity = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return SocialCredentialResult.Error(TRY_AGAIN)
        val request =
            GetCredentialRequest
                .Builder()
                .addCredentialOption(
                    GetSignInWithGoogleOption
                        .Builder(serverClientId)
                        .setNonce(nonce)
                        .build(),
                ).build()
        return try {
            val response = credentialManager.getCredential(context = activity, request = request)
            response.credential.toResult()
        } catch (_: GetCredentialCancellationException) {
            SocialCredentialResult.Cancelled
        } catch (_: NoCredentialException) {
            // The button flow can add an account, so this is rare: no account was picked or added.
            SocialCredentialResult.Error("No Google account was chosen. Add one in your phone's settings, or sign up with email.")
        } catch (_: GetCredentialProviderConfigurationException) {
            // No usable Google Play services / credential provider on this device.
            SocialCredentialResult.Unavailable
        } catch (_: GetCredentialUnsupportedException) {
            SocialCredentialResult.Unavailable
        } catch (_: GetCredentialException) {
            SocialCredentialResult.Error(TRY_AGAIN)
        }
    }

    /** Lets Google show the full account choice next time instead of preferring the last account. */
    override suspend fun onSignedOut() {
        if (serverClientId.isBlank()) return
        runCatchingCancellable { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
    }

    private fun Credential.toResult(): SocialCredentialResult {
        if (this !is CustomCredential || type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return SocialCredentialResult.Error(TRY_AGAIN)
        }
        return try {
            val google = GoogleIdTokenCredential.createFrom(data)
            SocialCredentialResult.Success(idToken = google.idToken, givenName = google.givenName)
        } catch (_: GoogleIdTokenParsingException) {
            SocialCredentialResult.Error(TRY_AGAIN)
        }
    }

    private companion object {
        const val TRY_AGAIN = "Google sign-in didn't work. Try again."
    }
}
