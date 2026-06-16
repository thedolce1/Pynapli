package com.pynapli.app

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

internal fun pynapliAppCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
