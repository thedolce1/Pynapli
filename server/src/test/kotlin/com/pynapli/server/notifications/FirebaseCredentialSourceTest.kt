package com.pynapli.server.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.pynapli.server.config.ServerConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Pins the Firebase credential precedence: explicit service-account (inline JSON, then file path)
 * wins, otherwise the server uses Application Default Credentials — the keyless gcloud-ADC / Cloud
 * Run path. Guards the change that lets FCM authenticate without a downloaded JSON key.
 */
class FirebaseCredentialSourceTest {
    @Test
    fun defaultsToApplicationDefaultWhenNoExplicitCredentials() {
        assertEquals(FirebaseCredentialSource.APPLICATION_DEFAULT, firebaseCredentialSource(config()))
    }

    @Test
    fun usesFilePathWhenOnlyPathIsSet() {
        assertEquals(
            FirebaseCredentialSource.FILE_PATH,
            firebaseCredentialSource(config(path = "/secure/pynapli-sa.json")),
        )
    }

    @Test
    fun usesInlineJsonWhenSet() {
        assertEquals(
            FirebaseCredentialSource.INLINE_JSON,
            firebaseCredentialSource(config(json = """{"type":"service_account"}""")),
        )
    }

    @Test
    fun inlineJsonWinsOverFilePath() {
        assertEquals(
            FirebaseCredentialSource.INLINE_JSON,
            firebaseCredentialSource(config(json = """{"type":"service_account"}""", path = "/secure/pynapli-sa.json")),
        )
    }

    @Test
    fun resolveFallsBackToApplicationDefaultCredentialsWhenNoneConfigured() {
        // No explicit credentials -> the injected ADC supplier is used (no JSON key needed).
        val sentinel = GoogleCredentials.newBuilder().build()
        assertSame(sentinel, resolveFirebaseCredentials(config()) { sentinel })
    }

    private fun config(
        json: String? = null,
        path: String? = null,
    ): ServerConfig =
        ServerConfig(
            environment = "test",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
            firebaseServiceAccountJson = json,
            firebaseServiceAccountPath = path,
        )
}
