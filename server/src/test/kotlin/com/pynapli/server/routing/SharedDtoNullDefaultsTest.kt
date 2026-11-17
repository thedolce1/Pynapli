package com.pynapli.server.routing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The server writes JSON with `explicitNulls = false`, so a null field is left out of the response.
 * The app decodes with the shared DTOs, where a nullable property WITHOUT a default is still a
 * required key: one omitted null makes the whole response fail to decode, and the screen silently
 * keeps stale data. (A Grid card with distance hidden broke the whole Grid page this way.)
 * Every nullable property in the shared DTOs must default to null.
 */
class SharedDtoNullDefaultsTest {
    @Test
    fun everyNullableSharedDtoPropertyDefaultsToNull() {
        val dtoDir = repositoryRoot().resolve("shared/src/commonMain/kotlin/com/pynapli/shared/network/dto")
        val offenders =
            Files
                .list(dtoDir)
                .use { files ->
                    files
                        .filter { it.name.endsWith(".kt") }
                        .sorted()
                        .toList()
                }.flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (NULLABLE_WITHOUT_DEFAULT.matches(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
                    }
                }
        assertEquals(emptyList(), offenders, "Give these `= null` so an omitted null still decodes.")
    }

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("settings.gradle.kts")) && Files.exists(current.resolve("shared"))) return current
            current = current.parent ?: current
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}.")
    }

    private companion object {
        val NULLABLE_WITHOUT_DEFAULT = Regex("""^\s+val \w+: [A-Za-z<>, ]+\?,\s*$""")
    }
}
