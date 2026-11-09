package com.pynapli.app

import com.pynapli.app.auth.DevLoginCredentials

/** No dev login outside debug builds; the debug copy of this file holds the local credentials. */
internal fun devLoginDefaults(): DevLoginCredentials? = null
