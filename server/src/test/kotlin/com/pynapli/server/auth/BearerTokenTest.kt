package com.pynapli.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BearerTokenTest {
    @Test
    fun extractsTheToken() {
        assertEquals("abc123", parseBearerToken("Bearer abc123"))
        assertEquals("abc123", parseBearerToken("Bearer   abc123  "))
    }

    // Regression: the per-route copies accepted the scheme in any case but then split on the exact
    // "Bearer ", so a lowercase header produced the whole value ("bearer abc123") as the token.
    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals("abc123", parseBearerToken("bearer abc123"))
        assertEquals("abc123", parseBearerToken("BEARER abc123"))
    }

    @Test
    fun rejectsMissingOtherSchemeOrEmptyToken() {
        assertNull(parseBearerToken(null))
        assertNull(parseBearerToken(""))
        assertNull(parseBearerToken("Basic dXNlcjpwYXNz"))
        assertNull(parseBearerToken("Bearer"))
        assertNull(parseBearerToken("Bearer    "))
        assertNull(parseBearerToken("Bearerabc123"))
    }
}
