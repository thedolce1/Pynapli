package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliSectionIntro
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.PynapliSheetActionRow
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.PynapliSheetTitle
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.icons.filled.Place
import com.pynapli.app.design.icons.filled.Search
import com.pynapli.app.design.icons.filled.VerifiedUser
import com.pynapli.app.location.TravelLocationStateHolder
import com.pynapli.app.location.TravelLocationUiState
import com.pynapli.shared.core.config.LegalLinks
import com.pynapli.shared.network.dto.TravelPlaceDto
import com.pynapli.shared.network.dto.TravelTripDto
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Travel mode picker (SWIPE_IMPLEMENTATION_SPEC L339-354).
 *
 * Free-text UX (founder decision 2026-06-09): type a city name or ZIP/postal code
 * — e.g. a Toronto user typing "Las Vegas" or "89101" before a weekend trip — and
 * pick from server-resolved suggestions. When a destination is active, the sheet
 * shows it with a "Clear travel mode" action. Free-tier users see a paywall hint
 * instead of the picker — the server returns `ENTITLEMENT_REQUIRED` and the sheet
 * folds the picker to the paywall copy.
 */
@Composable
internal fun TravelModeSheet(
    state: TravelLocationUiState,
    onDismiss: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onRunSearch: () -> Unit,
    onPickPlace: (TravelPlaceDto) -> Unit,
    onClear: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAgeVerification: () -> Unit = {},
    onSelectPlace: ((TravelPlaceDto?) -> Unit)? = null,
    onPlanTrip: (place: TravelPlaceDto, startsOn: String, endsOn: String, announce: Boolean) -> Unit = { _, _, _, _ -> },
    onCancelTrip: (tripId: String) -> Unit = {},
) {
    // Debounced type-ahead: re-query when the user pauses typing.
    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery.trim().length >= TravelLocationStateHolder.MIN_SEARCH_QUERY_LENGTH) {
            delay(SEARCH_DEBOUNCE_MILLIS)
            onRunSearch()
        }
    }

    PynapliBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(TravelModeTags.SHEET),
    ) {
        PynapliSheetColumn {
            PynapliSheetTitle(text = "Travel mode")
            PynapliSectionIntro(
                text =
                    "Type a city or ZIP/postal code and your Grid and deck show people there. " +
                        "One location at a time. Clear it to go back to local.",
            )

            if (state.ageVerificationRequired) {
                TravelVerificationBanner(onVerify = onOpenAgeVerification)
            } else if (state.entitlementRequired) {
                if (state.previewUsed) {
                    Text(
                        text = "You've used this month's Travel preview.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TravelPaywallBanner(onSubscribe = onOpenStore)
            } else {
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.current?.let { dto ->
                    Text(
                        text = "Currently in ${dto.city}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.trips.isNotEmpty()) TravelTripsList(trips = state.trips, onCancel = onCancelTrip)
                val selected = state.selectedPlace
                if (selected != null && onSelectPlace != null) {
                    TravelGoOrPlan(
                        place = selected,
                        isSaving = state.isSaving,
                        onGoNow = { onPickPlace(selected) },
                        onPlan = { startsOn, endsOn, announce -> onPlanTrip(selected, startsOn, endsOn, announce) },
                        onBack = { onSelectPlace(null) },
                    )
                }
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(TravelModeTags.SEARCH_FIELD),
                    placeholder = { Text(text = "City, ZIP, or postal code") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon =
                        if (state.isSearching) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            null
                        },
                    singleLine = true,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.searchResults.forEach { place ->
                        // With trip planning wired, a pick first asks "Go now" or "Plan dates".
                        TravelPlaceRow(place = place, onPick = { onSelectPlace?.invoke(place) ?: onPickPlace(place) })
                    }
                    if (
                        state.searchResults.isEmpty() &&
                        !state.isSearching &&
                        state.searchQuery.trim().length >= TravelLocationStateHolder.MIN_SEARCH_QUERY_LENGTH
                    ) {
                        Text(
                            text = "No places match yet — keep typing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    if (state.searchResults.isNotEmpty()) {
                        // CC BY 4.0: attribute the data where it is shown.
                        Text(
                            text = LegalLinks.GEONAMES_ATTRIBUTION,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp).testTag(TravelModeTags.ATTRIBUTION),
                        )
                    }
                }
            }

            PynapliSheetActionRow(
                start =
                    if (state.isActive && !state.entitlementRequired && !state.ageVerificationRequired) {
                        {
                            TextButton(
                                onClick = onClear,
                                modifier = Modifier.testTag(TravelModeTags.CLEAR).handCursor(),
                            ) { Text(text = "Clear travel mode") }
                        }
                    } else {
                        null
                    },
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.handCursor()) { Text(text = "Close") }
            }
        }
    }
}

/** Planned trips, soonest first, each with a way to call it off. */
@Composable
private fun TravelTripsList(
    trips: List<TravelTripDto>,
    onCancel: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(TravelModeTags.TRIPS)) {
        Text(text = "Planned trips", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trips.forEach { trip ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text =
                        "${trip.city} · ${tripDateRange(trip.startsOn, trip.endsOn)}" +
                            (if (trip.status == "active") " · now" else "") +
                            (if (trip.announce && trip.status != "active") " · locals can see" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { onCancel(trip.tripId) }, modifier = Modifier.handCursor()) { Text("Cancel") }
            }
        }
    }
}

/** The picked place, with "Go now" (switch today) or "Plan dates" (switch on and off by itself). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TravelGoOrPlan(
    place: TravelPlaceDto,
    isSaving: Boolean,
    onGoNow: () -> Unit,
    onPlan: (startsOn: String, endsOn: String, announce: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    // Off by default: a trip is private unless the traveller chooses to be seen before arriving.
    var announce by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(TravelModeTags.GO_OR_PLAN)) {
        Text(text = place.displayName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = "Go now, or pick your dates and Travel mode turns on when you arrive and off when you leave.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().testTag(TravelModeTags.ANNOUNCE),
        ) {
            Text(
                text = "Let people in ${place.city} see I'm coming (shown up to 2 weeks before a planned trip)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = announce, onCheckedChange = { announce = it }, modifier = Modifier.handCursor())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onGoNow, enabled = !isSaving, modifier = Modifier.handCursor(enabled = !isSaving)) { Text("Go now") }
            OutlinedButton(onClick = { picking = true }, enabled = !isSaving, modifier = Modifier.handCursor(enabled = !isSaving)) { Text("Plan dates") }
            TextButton(onClick = onBack, modifier = Modifier.handCursor()) { Text("Back") }
        }
    }
    if (picking) {
        val rangeState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                val start = rangeState.selectedStartDateMillis
                val end = rangeState.selectedEndDateMillis ?: start
                TextButton(
                    onClick = {
                        if (start != null && end != null) onPlan(start.toIsoDate(), end.toIsoDate(), announce)
                        picking = false
                    },
                    enabled = start != null,
                    modifier = Modifier.handCursor(enabled = start != null),
                ) { Text("Plan trip") }
            },
            dismissButton = { TextButton(onClick = { picking = false }, modifier = Modifier.handCursor()) { Text("Cancel") } },
        ) {
            DateRangePicker(state = rangeState, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** The picker reports UTC-midnight millis; the server wants the calendar date. */
private fun Long.toIsoDate(): String =
    Instant
        .fromEpochMilliseconds(this)
        .toLocalDateTime(TimeZone.UTC)
        .date
        .toString()

/** "14–17 Nov" (or "28 Nov – 2 Dec", or "14 Nov" for one day). */
internal fun tripDateRange(
    startsOn: String,
    endsOn: String,
): String {
    val start = runCatching { LocalDate.parse(startsOn) }.getOrNull() ?: return startsOn
    val end = runCatching { LocalDate.parse(endsOn) }.getOrNull() ?: return startsOn
    val month = { date: LocalDate ->
        date.month.name
            .take(3)
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }
    return when {
        start == end -> "${start.day} ${month(start)}"
        start.month == end.month && start.year == end.year -> "${start.day}–${end.day} ${month(end)}"
        else -> "${start.day} ${month(start)} – ${end.day} ${month(end)}"
    }
}

@Composable
private fun TravelPlaceRow(
    place: TravelPlaceDto,
    onPick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .handCursor()
                .clickable(onClick = onPick)
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .testTag(TravelModeTags.placeTag(place.displayName)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = place.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TravelPaywallBanner(onSubscribe: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(TravelModeTags.PAYWALL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Travel mode is included with Pynapli Select.",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The partner benefit, as one quiet line under the primary message.
            Text(
                text = "Select covers your linked partner too.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = onSubscribe,
            modifier = Modifier.testTag(TravelModeTags.PAYWALL_SUBSCRIBE).handCursor(),
        ) { Text(text = "Subscribe", maxLines = 1) }
    }
}

/**
 * Surfaced when the server rejects a travel-region change with AGE_VERIFICATION_REQUIRED
 * (e.g. a restricted region requires age verification). Routes into the Profile-Hub
 * age-verification flow rather than the Store. Gold accent per the design north-star.
 */
@Composable
private fun TravelVerificationBanner(onVerify: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(TravelModeTags.VERIFICATION),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = PynapliColors.Accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Verify your age to set travel mode for this region.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onVerify,
            modifier = Modifier.testTag(TravelModeTags.VERIFICATION_VERIFY).handCursor(),
        ) { Text(text = "Verify", maxLines = 1) }
    }
}

private const val SEARCH_DEBOUNCE_MILLIS = 300L

internal object TravelModeTags {
    const val ATTRIBUTION = "travel_mode_attribution"
    const val TRIPS = "travel_mode_trips"
    const val GO_OR_PLAN = "travel_mode_go_or_plan"
    const val ANNOUNCE = "travel_mode_announce"
    const val OPEN_SHEET: String = "travel-mode-open-sheet"
    const val SHEET: String = "travel-mode-sheet"
    const val CLEAR: String = "travel-mode-clear"
    const val SEARCH_FIELD: String = "travel-mode-search-field"
    const val PAYWALL: String = "travel-mode-paywall"
    const val PAYWALL_SUBSCRIBE: String = "travel-mode-paywall-subscribe"
    const val VERIFICATION: String = "travel-mode-verification"
    const val VERIFICATION_VERIFY: String = "travel-mode-verification-verify"

    fun placeTag(displayName: String): String = "travel-mode-place-$displayName"
}
