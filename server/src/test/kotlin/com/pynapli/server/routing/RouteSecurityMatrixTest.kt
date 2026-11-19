package com.pynapli.server.routing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteSecurityMatrixTest {
    @Test
    fun everyRouteFileIsRepresentedInSecurityMatrix() {
        val root = repositoryRoot()
        val matrix = root.resolve("docs/SECURITY_ROUTE_MATRIX.md").readText()
        val routeFiles = routeFiles(root)

        val missing =
            routeFiles
                .map { it.name }
                .filterNot { fileName -> matrix.contains("`$fileName`") }

        assertEquals(emptyList(), missing, "Every Ktor route file must be classified in SECURITY_ROUTE_MATRIX.md.")
    }

    @Test
    fun routeFilesDeclareAnAuthGateOrAreExplicitlyPublic() {
        val root = repositoryRoot()
        val missingGate =
            routeFiles(root)
                .filterNot { it.name in EXPLICIT_PUBLIC_ROUTE_FILES }
                .filterNot { routeAuthGateRegex.containsMatchIn(it.readText()) }
                .map { it.name }

        assertEquals(emptyList(), missingGate, "Private/admin/webhook route files must declare an auth gate.")
    }

    @Test
    fun localMediaUploadProxyStaysLocalOrTestOnly() {
        val root = repositoryRoot()
        val mediaRoutes = root.resolve("server/src/main/kotlin/com/pynapli/server/media/MediaRoutes.kt").readText()
        val mediaDependencies = root.resolve("server/src/main/kotlin/com/pynapli/server/dependencies/MediaDependencies.kt").readText()

        assertTrue(
            mediaRoutes.contains("localUploadStorage?.let"),
            "Local upload proxy routes should only be registered through LocalMediaUploadStorage wiring.",
        )
        assertTrue(
            mediaDependencies.contains("Local media upload storage is allowed only for local/test environments"),
            "Production-like environments must not expose the local upload proxy.",
        )
    }

    @Test
    fun devSignupShortcutsStayLocalOnly() {
        val root = repositoryRoot()
        val signupRoutes = root.resolve("server/src/main/kotlin/com/pynapli/server/auth/EmailSignupRoutes.kt").readText()
        val signupDependencies = root.resolve("server/src/main/kotlin/com/pynapli/server/dependencies/EmailSignupDependencies.kt").readText()

        assertTrue(
            signupRoutes.contains("devMailbox?.let"),
            "Dev sign-up routes should only be registered when the local mailbox is wired.",
        )
        assertTrue(
            Regex("""if \(!call\.isDirectLoopbackRequest\(\)\)""").findAll(signupRoutes).count() == 2,
            "Both dev sign-up routes must refuse anything but a direct loopback request.",
        )
        assertTrue(
            signupDependencies.contains("localMailer?.takeIf { config.devSignupShortcutsAllowed() }"),
            "The local mailbox must only be handed to the routes when the dev shortcuts are allowed.",
        )
    }

    private fun routeFiles(root: Path): List<Path> =
        Files
            .walk(root.resolve("server/src/main/kotlin/com/pynapli/server"))
            .use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.name.endsWith("Routes.kt") }
                    .sorted(Comparator.comparing { it.toString() })
                    .toList()
            }

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("settings.gradle.kts")) && Files.exists(current.resolve("docs"))) {
                return current
            }
            current = current.parent ?: current
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}.")
    }

    private companion object {
        // EmailSignupRoutes: sign-up, verify and resend run before there is any account (rate-limited,
        // membership-private); its dev helpers are local-only (see the test above).
        private val EXPLICIT_PUBLIC_ROUTE_FILES = setOf("HealthRoutes.kt", "EmailSignupRoutes.kt")

        private val routeAuthGateRegex =
            Regex(
                pattern =
                    listOf(
                        "authService\\.authenticate",
                        "authenticatedUser\\(",
                        // Realtime streams: both authenticate the socket before anything is sent.
                        "authenticateStream\\(",
                        "recipientStream\\(",
                        "bearerToken\\(",
                        "bearerTokenOrNull\\(",
                        "requiredBearerToken\\(",
                        "requireAdminActor",
                        "requireSubscriptionAdminActor",
                        "requirePlayStoreRtdnAuth",
                    ).joinToString("|"),
            )
    }
}
