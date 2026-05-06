package com.pynapli.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService

class PynapliFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AndroidPushRegistrationStore(applicationContext).clear()
    }
}
