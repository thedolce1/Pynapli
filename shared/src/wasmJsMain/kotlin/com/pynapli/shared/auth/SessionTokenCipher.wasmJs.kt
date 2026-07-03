package com.pynapli.shared.auth

// Browsers expose no app-controlled keystore, so web session values ride the session
// store's legacy-plaintext compatibility path (same as desktop JVM). Web-side mitigations
// live in :webApp instead: strict CSP, no third-party scripts, and access tokens kept in
// memory with only the refresh token persisted.
actual fun createPlatformSessionTokenCipher(): SessionTokenCipher = PassThroughSessionTokenCipher
