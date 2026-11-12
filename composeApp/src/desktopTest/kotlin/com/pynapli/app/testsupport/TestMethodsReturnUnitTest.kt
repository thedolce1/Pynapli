package com.pynapli.app.testsupport

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JUnit silently skips a test method that returns a value, and Kotlin makes that easy:
 * `fun x() = runBlocking { ...; someCall() }` returns whatever the last line returns. Such a test
 * reports nothing and never runs (two did exactly that before this guard existed). This fails the
 * build instead.
 */
class TestMethodsReturnUnitTest {
    @Test
    fun everyTestMethodReturnsUnitSoItActuallyRuns() {
        val root =
            File(
                TestMethodsReturnUnitTest::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val loader = TestMethodsReturnUnitTest::class.java.classLoader
        val tests =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map {
                    it
                        .relativeTo(root)
                        .path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                }.mapNotNull { name -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
                .flatMap { type -> runCatching { type.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
                .filter { method -> method.annotations.any { it.annotationClass.qualifiedName in TEST_ANNOTATIONS } }
                .toList()
        val offenders =
            tests
                .filter { method -> method.returnType != Void.TYPE || Modifier.isStatic(method.modifiers) }
                .map { method -> "${method.declaringClass.name}.${method.name} returns ${method.returnType.simpleName}" }

        // Guard against the guard itself finding nothing (wrong directory or annotation name).
        assertTrue(tests.size > 50, "Expected to find this suite's tests; found ${tests.size}")

        assertTrue(offenders.isEmpty(), "These tests never run; make them return Unit (e.g. runBlocking<Unit>):\n" + offenders.joinToString("\n"))
    }

    private companion object {
        val TEST_ANNOTATIONS = setOf("org.junit.jupiter.api.Test", "org.junit.Test")
    }
}
