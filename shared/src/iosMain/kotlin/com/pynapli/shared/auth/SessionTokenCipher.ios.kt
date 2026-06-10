package com.pynapli.shared.auth

// iOS Keychain-backed cipher lands with the iOS phase (out of the Android
// production scope, ANDROID_PRODUCTION_GAMEPLAN); until then sessions on iOS
// dev builds stay pass-through like before this slice.
actual fun createPlatformSessionTokenCipher(): SessionTokenCipher = PassThroughSessionTokenCipher
