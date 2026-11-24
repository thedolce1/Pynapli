package com.pynapli.shared.settings

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * What the signed-in account on this device chose about sharing its location (GRID_IMPLEMENTATION_SPEC,
 * Location Strategy). An OS or browser grant is per install, so it cannot say what *this* account
 * chose: without this, account B's sign-up would be sent a fix silently because account A once
 * allowed location, and a member who tapped "Skip for now" would be sent one on every resume.
 *
 * One record, for the current account only, keyed by its [userId]: a record for any other id counts
 * as none. It is cleared on sign-out and re-derived at the next sign-in (`LocationSharingGate`).
 * Device-local, and its own key with its own writer (like [OnboardingProgress]), so no other
 * read-merge-save can clobber it. Holds an opaque server user id only, never a location.
 */
@Serializable
data class LocationSharingConsent(
    val userId: String? = null,
    /** This account shared from inside the app, or was seen to already have a location on the server. */
    val shared: Boolean = false,
    /** This account tapped "Skip for now". Wins over everything except its own later Share. */
    val skipped: Boolean = false,
) {
    /** This record, if it is [userId]'s; otherwise a blank one for [userId]. */
    fun forUser(userId: String): LocationSharingConsent = if (this.userId == userId) this else LocationSharingConsent(userId = userId)
}

interface LocationSharingConsentStore {
    suspend fun load(): LocationSharingConsent

    suspend fun save(consent: LocationSharingConsent)
}

/** For shells without a silent location refresh, and tests. */
class InMemoryLocationSharingConsentStore : LocationSharingConsentStore {
    private var consent = LocationSharingConsent()

    override suspend fun load(): LocationSharingConsent = consent

    override suspend fun save(consent: LocationSharingConsent) {
        this.consent = consent
    }
}

class SqlDelightLocationSharingConsentStore(
    private val database: PynapliDatabase,
    private val json: Json = PynapliJson,
) : LocationSharingConsentStore {
    override suspend fun load(): LocationSharingConsent {
        val value =
            database.appMetaQueries
                .selectByKey(LOCATION_SHARING_CONSENT_KEY)
                .executeAsOneOrNull()
                ?.value_
                ?: return LocationSharingConsent()
        return runCatching { json.decodeFromString(LocationSharingConsent.serializer(), value) }
            .getOrElse { LocationSharingConsent() }
    }

    override suspend fun save(consent: LocationSharingConsent) {
        database.appMetaQueries.upsert(
            key = LOCATION_SHARING_CONSENT_KEY,
            value_ = json.encodeToString(LocationSharingConsent.serializer(), consent),
            updated_at_epoch_ms = currentTimeMillis(),
        )
        // v1 kept a list of up to 8 accounts' ids; nothing on the device needs that list any more.
        database.appMetaQueries.deleteByKey(LEGACY_LIST_KEY)
    }

    private companion object {
        private const val LOCATION_SHARING_CONSENT_KEY = "location.sharing-consent.v2"
        private const val LEGACY_LIST_KEY = "location.sharing-consent.v1"

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
