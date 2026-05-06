package com.pynapli.app.notifications

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidFirebasePushTokenProvider(
    context: Context,
    private val fallbackProvider: PushTokenProvider = AndroidDevPushTokenProvider(context)
) : PushTokenProvider {
    private val appContext = context.applicationContext

    override suspend fun currentToken(): String? {
        val firebaseToken = runCatching {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                null
            } else {
                FirebaseMessaging.getInstance().token.awaitOrNull()
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        return firebaseToken ?: fallbackProvider.currentToken()
    }
}

private suspend fun <T> Task<T>.awaitOrNull(): T? =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resume(null)
            }
        }
    }
