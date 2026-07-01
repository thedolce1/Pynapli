package com.pynapli.server.admin

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminRequestAuditRequiredTest {
    @Test
    fun successfulAdminMutationsAreAudited() {
        assertTrue(adminAuditRequired("POST", "/v1/admin/reports/123/resolve", 200))
        assertTrue(adminAuditRequired("PUT", "/v1/admin/subscriptions/entitlements", 200))
        assertTrue(adminAuditRequired("PATCH", "/v1/admin/things/1", 204))
        assertTrue(adminAuditRequired("DELETE", "/v1/admin/things/1", 200))
        // Method comparison is case-insensitive.
        assertTrue(adminAuditRequired("post", "/v1/admin/things/1", 201))
    }

    @Test
    fun readsNonAdminPathsAndFailuresAreNotAudited() {
        assertFalse(adminAuditRequired("GET", "/v1/admin/users/1", 200)) // read, not a mutation
        assertFalse(adminAuditRequired("POST", "/v1/messages", 200)) // outside the admin surface
        assertFalse(adminAuditRequired("POST", "/v1/adminfoo/x", 200)) // not the /v1/admin/ prefix
        assertFalse(adminAuditRequired("POST", "/v1/admin/things/1", 403)) // rejected
        assertFalse(adminAuditRequired("POST", "/v1/admin/things/1", 500)) // errored
    }
}
