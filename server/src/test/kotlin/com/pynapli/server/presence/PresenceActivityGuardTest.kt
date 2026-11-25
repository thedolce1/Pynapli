package com.pynapli.server.presence

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Privacy review F3, pinned at the call sites: signing out, signing other sessions out, deleting the
 * account, the rate-limit plugin's token lookup and a socket's re-auth tick must never count as
 * activity (they use `authenticateWithoutActivity`), and account deletion ends presence. Logout needs no
 * pin here: it never calls authenticate, and `AuthServiceTest` proves it ends presence.
 */
class PresenceActivityGuardTest {
    private val serverMain = repositoryRoot().resolve("server/src/main/kotlin/com/pynapli/server")

    private fun source(relative: String): String = serverMain.resolve(relative).readText()

    /** The text of the route block that starts at [marker], up to the next route declaration. */
    private fun block(
        text: String,
        marker: String,
    ): String {
        val start = text.indexOf(marker)
        assertTrue(start >= 0, "route not found: $marker")
        val next = Regex("""\n\s*(get|post|put|patch|delete|webSocket)\(""").find(text, start + marker.length)
        return text.substring(start, next?.range?.first ?: text.length)
    }

    @Test
    fun signOutAndDeletionRoutesDoNotCountAsActivity() {
        val auth = source("auth/AuthRoutes.kt")
        listOf("delete(\"/sessions/{sessionId}\")", "post(\"/sessions/sign-out-others\")").forEach { marker ->
            val route = block(auth, marker)
            assertTrue("authenticateWithoutActivity(" in route, "$marker must not count as activity")
            assertTrue("authService.authenticate(" !in route, "$marker must not count as activity")
        }
        assertTrue("authService.authenticate(" !in block(auth, "post(\"/logout\")"), "logout must not count as activity")

        val deletion = block(source("account/AccountLifecycleRoutes.kt"), "post(\"/v1/account/delete\")")
        assertTrue("authenticateWithoutActivity(" in deletion && "authService.authenticate(" !in deletion)
        assertTrue("authService.endPresence(" in deletion, "account deletion must end presence")
    }

    @Test
    fun theRateLimitLookupAndTheSocketReAuthTickDoNotCountAsActivity() {
        val application = source("Application.kt")
        val resolve = application.lines().single { "resolveUserId =" in it }
        assertTrue("authenticateWithoutActivity(" in resolve, resolve)

        val stream = source("realtime/RealtimeStreamSupport.kt")
        val tick = stream.substring(stream.indexOf("withTimeoutOrNull(recheckEveryMillis)"))
        assertTrue(tick.indexOf("authenticateWithoutActivity(") in 0 until tick.indexOf("return viewerId"), "the re-auth tick must not count as activity")
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
}
