package com.pynapli.shared.settings

import com.pynapli.shared.storage.PynapliDatabase

/**
 * Device storage keys of the retired location-sharing consent record (a list of up to 8 account ids,
 * then a single record). The silent location refresh no longer stores anything; each shell deletes
 * these on launch so no account id lingers on the device.
 */
object RetiredLocationConsentKeys {
    /** SQLDelight `app_meta` keys (written only by Android; iOS never stored them). */
    val appMetaKeys: List<String> = listOf("location.sharing-consent.v1", "location.sharing-consent.v2")

    /** Browser `localStorage` keys (web). */
    val webStorageKeys: List<String> = listOf("pynapli.location.sharing-consent.v1", "pynapli.location.sharing-consent.v2")
}

/** Deletes [RetiredLocationConsentKeys.appMetaKeys]; idempotent, so it runs on every launch. */
fun PynapliDatabase.deleteRetiredLocationConsentKeys() {
    RetiredLocationConsentKeys.appMetaKeys.forEach { key -> appMetaQueries.deleteByKey(key) }
}
