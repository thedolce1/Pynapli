package com.pynapli.app

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

class PynapliApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializeFirebaseAppCheck()
    }

    private fun initializeFirebaseAppCheck() {
        runCatching {
            FirebaseApp.initializeApp(this)
            if (FirebaseApp.getApps(this).isEmpty()) return

            FirebaseAppCheck
                .getInstance()
                .installAppCheckProviderFactory(pynapliAppCheckProviderFactory())
        }.onFailure { error ->
            Log.w(TAG, "Firebase App Check initialization skipped.", error)
        }
    }
}

private const val TAG = "PynapliApplication"
