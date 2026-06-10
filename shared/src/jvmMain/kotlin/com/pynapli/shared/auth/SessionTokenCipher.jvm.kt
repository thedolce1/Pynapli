package com.pynapli.shared.auth

// Desktop JVM is a development/test target without a device keystore; the session
// store keeps its legacy-plaintext compatibility path for these values.
actual fun createPlatformSessionTokenCipher(): SessionTokenCipher = PassThroughSessionTokenCipher
