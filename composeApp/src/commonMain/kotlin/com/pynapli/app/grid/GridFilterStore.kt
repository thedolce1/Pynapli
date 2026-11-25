package com.pynapli.app.grid

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.storage.PynapliDatabase
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Remembers the viewer's Grid filters across cold starts (plan G9), on this device only. It holds
 * the filter choices and nothing else: no cards, no location, no cursor. Stored in the same shape as
 * a saved preset (`GridQueryRequestDto`, minus the cursor), so unknown tokens from an older build
 * drop silently on the way back in and the server re-validates on the next query. Sign-out clears it.
 */
interface GridFilterStore {
    suspend fun load(): GridFilters?

    suspend fun save(filters: GridFilters)

    suspend fun clear()
}

object NoOpGridFilterStore : GridFilterStore {
    override suspend fun load(): GridFilters? = null

    override suspend fun save(filters: GridFilters) = Unit

    override suspend fun clear() = Unit
}

internal fun encodeGridFilters(
    filters: GridFilters,
    json: Json = PynapliJson,
): String = json.encodeToString(GridQueryRequestDto.serializer(), filters.toGridQueryRequest(cursor = null))

/** A corrupt or foreign blob reads as "nothing saved", never as an error that blocks the Grid. */
internal fun decodeGridFilters(
    value: String?,
    json: Json = PynapliJson,
): GridFilters? {
    if (value.isNullOrBlank()) return null
    return runCatching { GridFilters.fromGridQueryRequest(json.decodeFromString(GridQueryRequestDto.serializer(), value)) }.getOrNull()
}

/** Android and iOS: the `app_meta` key-value table, as the Grid snapshot and app settings use. */
class SqlDelightGridFilterStore(
    private val database: PynapliDatabase,
) : GridFilterStore {
    override suspend fun load(): GridFilters? =
        decodeGridFilters(
            database.appMetaQueries
                .selectByKey(GRID_FILTERS_KEY)
                .executeAsOneOrNull()
                ?.value_,
        )

    override suspend fun save(filters: GridFilters) {
        database.appMetaQueries.upsert(
            key = GRID_FILTERS_KEY,
            value_ = encodeGridFilters(filters),
            updated_at_epoch_ms = currentTimeMillis(),
        )
    }

    override suspend fun clear() {
        database.appMetaQueries.deleteByKey(GRID_FILTERS_KEY)
    }

    @OptIn(ExperimentalTime::class)
    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val GRID_FILTERS_KEY = "grid_filters_v1"
    }
}

/**
 * Web (`localStorage`) and the dev desktop shell (a file): a named string blob. Filter choices only,
 * which is why `localStorage` is acceptable here while the card snapshot stays in `sessionStorage`.
 */
class KeyValueGridFilterStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val remove: () -> Unit,
) : GridFilterStore {
    override suspend fun load(): GridFilters? = decodeGridFilters(read())

    override suspend fun save(filters: GridFilters) = write(encodeGridFilters(filters))

    override suspend fun clear() = remove()
}
