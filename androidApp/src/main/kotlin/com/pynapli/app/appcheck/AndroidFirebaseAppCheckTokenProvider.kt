package com.pynapli.app.appcheck

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.FirebaseAppCheck
import com.pynapli.shared.network.AppCheckTokenProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidFirebaseAppCheckTokenProvider(
    context: Context,
) : AppCheckTokenProvider {
    private val appContext = context.applicationContext

    override suspend fun appCheckToken(): String? =
        withTimeoutOrNull(APP_CHECK_TOKEN_TIMEOUT_MILLIS) {
            runCatching {
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    null
                } else {
                    FirebaseAppCheck
                        .getInstance()
                        .getAppCheckToken(false)
                        .awaitOrNull()
                        ?.token
                }
            }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
}

private suspend fun Task<AppCheckToken>.awaitOrNull(): AppCheckToken? =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }

private const val APP_CHECK_TOKEN_TIMEOUT_MILLIS = 1_500L
