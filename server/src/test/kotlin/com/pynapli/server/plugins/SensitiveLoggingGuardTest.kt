package com.pynapli.server.plugins

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals

/**
 * Forward-looking regression guard for the CLAUDE.md "do not log" rule (message bodies, private
 * media URLs, exact location, sensitive tags) plus raw secrets/tokens. Scans every `logger.*` call
 * in the server's main source and fails if its arguments reference a sensitive field. The
 * request-line redaction contract itself is pinned by [MonitoringPluginTest]; this catches new
 * direct log statements that would leak.
 *
 * Masked values (e.g. `purchaseToken.takeLast(6)`) are allowed via [MASKING_MARKERS]; if a real
 * leak is added, mask it or move the value out of the log rather than widening the allowlist.
 */
class SensitiveLoggingGuardTest {
    @Test
    fun noLoggerCallReferencesSensitiveFields() {
        val violations = mutableListOf<String>()
        serverMainKotlinFiles().forEach { file ->
            val source = file.readText()
            loggerCallTexts(source).forEach { call ->
                if (MASKING_MARKERS.any { call.contains(it) }) return@forEach
                val hit = FORBIDDEN_TOKENS.firstOrNull { call.contains(it, ignoreCase = true) }
                if (hit != null) {
                    violations += "${file.name}: logs \"$hit\" -> ${call.replace(Regex("\\s+"), " ").take(160)}"
                }
            }
        }

        assertEquals(
            emptyList(),
            violations,
            "logger.* calls must not reference sensitive fields (mask or move out of the log instead).",
        )
    }

    private fun serverMainKotlinFiles(): List<Path> =
        Files
            .walk(repositoryRoot().resolve("server/src/main/kotlin/com/pynapli/server"))
            .use { paths ->
                paths.filter { Files.isRegularFile(it) && it.name.endsWith(".kt") }.toList()
            }

    // Captures the argument text of a logger.<level>(...) call, balancing parentheses while ignoring
    // any that appear inside double-quoted string literals (e.g. log text like "swept {} session(s)").
    private fun loggerCallTexts(source: String): List<String> {
        val calls = mutableListOf<String>()
        for (match in LOGGER_CALL.findAll(source)) {
            val open = source.indexOf('(', match.range.first)
            if (open < 0) continue
            var depth = 0
            var inString = false
            var escaped = false
            var i = open
            while (i < source.length) {
                val c = source[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                calls += source.substring(open, i + 1)
                                break
                            }
                        }
                    }
                }
                i++
            }
        }
        return calls
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
        private val LOGGER_CALL = Regex("""logger\.(?:info|warn|error|debug|trace)\s*\(""")

        // Allowed because the value is masked/truncated, not logged in full.
        private val MASKING_MARKERS = listOf("takeLast", "takeFirst")

        private val FORBIDDEN_TOKENS =
            listOf(
                // Secrets / tokens
                "password",
                "accessToken",
                "refreshToken",
                "purchaseToken",
                "secret",
                // Message content
                "messageBody",
                "message.body",
                "messageText",
                // Exact location
                "latitude",
                "longitude",
                // Private media URLs
                "signedUrl",
                "mediaUrl",
                "downloadUrl",
                "publicContentUrl",
                // Sensitive profile tags
                "lifestyleTags",
            )
    }
}
