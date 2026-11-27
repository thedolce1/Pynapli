package com.pynapli.server.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every error code the server throws must have a user-safe message. Codes without one fall back to
 * "Request could not be completed.", which tells a person nothing (several Travel codes
 * did until 2026-11-12). New codes fail this test until they get a message or are listed below.
 */
class UserSafeMessageCoverageTest {
    @Test
    fun everyThrownErrorCodeHasAUserSafeMessage() {
        val thrown =
            serverMainKotlinFiles()
                .flatMap { file -> THROWN_CODE.findAll(file.readText()).map { it.groupValues[1] } }
                .toSortedSet()
        val missing = thrown.filter { code -> code !in NOT_USER_FACING && userSafeMessage(code) == GENERIC }

        assertEquals(emptyList(), missing, "Add a user-safe message in StatusPageMessages.kt for each code.")
    }

    private fun serverMainKotlinFiles(): List<Path> =
        Files.walk(repositoryRoot().resolve("server/src/main/kotlin/com/pynapli/server")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.name.endsWith(".kt") }.toList()
        }

    private fun repositoryRoot(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) dir = dir.parent
        return dir ?: error("repository root not found")
    }

    private companion object {
        const val GENERIC = "Request could not be completed."
        val THROWN_CODE = Regex("""[A-Za-z]+Exception\("([A-Z][A-Z0-9_]+)"\)""")

        // Codes a person never sees: admin/internal paths and provider callbacks. Keep this short.
        val NOT_USER_FACING =
            setOf(
                "ADMIN_MFA_REQUIRED", // admin console only
                "ADMIN_ROLE_REQUIRED", // admin console only
                "DEV_STORE_PURCHASE_UNAVAILABLE", // dev builds only
                "INVALID_NOTIFICATION_KIND", // server-internal enqueue guard
                "INVALID_RATE_LIMIT_ACTION", // server-internal programming error
                // Thumbnail worker outcomes (G2-2): recorded on the media row, never sent to a client.
                "EMPTY",
                "INVALID_DIMENSIONS",
                "NO_JPEG_WRITER",
                "TOO_MANY_PIXELS",
                "UNSUPPORTED_FORMAT",
            )
    }
}
