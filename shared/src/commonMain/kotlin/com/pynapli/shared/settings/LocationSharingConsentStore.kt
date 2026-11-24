package com.pynapli.shared.settings

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Which accounts on this device chose to share their location from inside the app (the Location
 * step's "Share my location", or the Grid's "Use current location"). An OS or browser grant is per
 * install, so it cannot say what *this* account chose: without this, account B's sign-up would be
 * sent a fix silently because account A once allowed location, and a member who tapped "Skip for
 * now" would be sent one on every resume. The silent resume refresh runs only for an account listed
 * here (GRID_IMPLEMENTATION_SPEC, Location Strategy).
 *
 * Device-local, and its own key with its own writer (like [OnboardingProgress]), so no other
 * read-merge-save can clobber it. Holds opaque server user ids only, never a location.
 */
@Serializable
data class LocationSharingConsent(
    /** Most recent last; capped at [MAX_ACCOUNTS] so a shared device's list stays small. */
    val sharedInAppUserIds: List<String> = emptyList(),
) {
    fun sharedInApp(userId: String): Boolean = userId in sharedInAppUserIds

    fun withShared(userId: String): LocationSharingConsent = copy(sharedInAppUserIds = (sharedInAppUserIds - userId + userId).takeLast(MAX_ACCOUNTS))

    fun withoutShared(userId: String): LocationSharingConsent = copy(sharedInAppUserIds = sharedInAppUserIds - userId)

    companion object {
        const val MAX_ACCOUNTS = 8
    }
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
    }

    private companion object {
        private const val LOCATION_SHARING_CONSENT_KEY = "location.sharing-consent.v1"

        @OptIn(ExperimentalTime::class)
        private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
}
