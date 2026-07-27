package com.pynapli.app

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Stable per-install identifier feeding the account-farm risk signal (TRUST_AND_SAFETY_SPEC).
 *
 * A random UUID minted on first launch and persisted in app-private preferences — deliberately
 * NOT a hardware identifier (Google Play discourages those, and a per-install random token is
 * non-identifying). The server hashes it before storing it in the device->account ledger, so the
 * raw value never lands in durable storage as a fingerprint. Resets on reinstall / clear-data,
 * which is the accepted trade-off for a privacy-safe, policy-compliant signal.
 */
internal fun stableInstallId(context: Context): String {
    val prefs = context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
    prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
    val id = UUID.randomUUID().toString()
    prefs.edit { putString(KEY_INSTALL_ID, id) }
    return id
}

private const val INSTALL_PREFS = "pynapli_install"
private const val KEY_INSTALL_ID = "install_id"
