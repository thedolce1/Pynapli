package com.pynapli.desktop

import com.pynapli.shared.core.config.PynapliEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The guard these cover is the reason the desktop shell is allowed to keep session tokens in a
 * plaintext file at all: it must be impossible to point this build at staging or prod by accident.
 */
class DesktopRuntimeConfigTest {
    @Test
    fun `defaults to the local server when no override is given`() {
        val config = resolveDesktopConfig(apiBaseUrl = null, allowRemote = false)

        assertEquals(DESKTOP_DEFAULT_API_BASE, config.apiBaseUrl)
        assertEquals(PynapliEnvironment.Local, config.environment)
        assertEquals("ws://localhost:8080", config.websocketBaseUrl)
    }

    @Test
    fun `treats a blank override as absent rather than as an empty url`() {
        val config = resolveDesktopConfig(apiBaseUrl = "   ", allowRemote = false)

        assertEquals(DESKTOP_DEFAULT_API_BASE, config.apiBaseUrl)
    }

    @Test
    fun `accepts the loopback address as local`() {
        val config = resolveDesktopConfig(apiBaseUrl = "http://127.0.0.1:8080", allowRemote = false)

        assertEquals(PynapliEnvironment.Local, config.environment)
        assertEquals("ws://127.0.0.1:8080", config.websocketBaseUrl)
    }

    @Test
    fun `strips a trailing slash so the websocket url is not malformed`() {
        val config = resolveDesktopConfig(apiBaseUrl = "http://localhost:8080/", allowRemote = false)

        assertEquals("http://localhost:8080", config.apiBaseUrl)
        assertEquals("ws://localhost:8080", config.websocketBaseUrl)
    }

    @Test
    fun `refuses a remote host unless remote access is explicitly allowed`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                resolveDesktopConfig(apiBaseUrl = "https://api.pynapli.com", allowRemote = false)
            }

        assertTrue(
            failure.message.orEmpty().contains("plaintext"),
            "the refusal should say WHY, so whoever hits it can judge the risk: ${failure.message}",
        )
    }

    /**
     * REGRESSION: the first version of this guard used
     * `resolved.startsWith("http://localhost")`, which is TRUE for
     * `http://localhost.attacker.example` — a remote host that would have been classified Local and
     * handed both the plaintext token store and the dev-login credentials. Host parsing, not prefix
     * matching, is what closes it.
     */
    @Test
    fun `refuses a remote host whose name merely begins with localhost`() {
        assertFailsWith<IllegalArgumentException> {
            resolveDesktopConfig(apiBaseUrl = "http://localhost.attacker.example", allowRemote = false)
        }
        assertFailsWith<IllegalArgumentException> {
            resolveDesktopConfig(apiBaseUrl = "https://localhost.attacker.example", allowRemote = false)
        }
        // Same trick against the loopback literal.
        assertFailsWith<IllegalArgumentException> {
            resolveDesktopConfig(apiBaseUrl = "http://127.0.0.1.attacker.example", allowRemote = false)
        }
    }

    @Test
    fun `refuses an unparseable url rather than defaulting it to local`() {
        assertFailsWith<IllegalArgumentException> {
            resolveDesktopConfig(apiBaseUrl = "not a url at all", allowRemote = false)
        }
    }

    @Test
    fun `allows a remote host when explicitly opted in, and marks it non-local`() {
        val config = resolveDesktopConfig(apiBaseUrl = "https://api.pynapli.com", allowRemote = true)

        assertEquals(PynapliEnvironment.Staging, config.environment)
        assertEquals("wss://api.pynapli.com", config.websocketBaseUrl)
    }
}
