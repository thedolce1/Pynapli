package com.pynapli.app

import com.pynapli.app.auth.DevLoginCredentials

/**
 * Debug builds only: the seeded local account behind the "Dev login" button. Staging and release
 * have their own copy of this file that returns null, so the password never ships in those APKs.
 */
internal fun devLoginDefaults(): DevLoginCredentials? =
    DevLoginCredentials(
        email = "dev@pynapli.local",
        password = "PynapliDevPass123!",
    )
