package com.pynapli.app.onboarding

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * Grindr-style date-of-birth wheel (founder ordering decision 2026-06-09): three
 * snap-scrolling columns (month / day / year). Any date in [yearRange] is pickable —
 * the 18+ gate is enforced on Continue so an underage pick gets the explicit stop
 * screen instead of silently impossible input.
 */
@Composable
internal fun DobWheelPicker(
    value: LocalDate,
    yearRange: IntRange,
    onValueChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            WheelColumn(
                itemCount = 12,
                selectedIndex = value.month.number - 1,
                label = { index -> monthNames[index] },
                onSelected = { index -> onValueChange(value.withMonthClamped(index + 1)) },
                modifier = Modifier.weight(1.2f),
            )
            WheelColumn(
                itemCount = daysInMonth(value.year, value.month.number),
                selectedIndex = value.day - 1,
                label = { index -> (index + 1).toString() },
                onSelected = { index -> onValueChange(LocalDate(value.year, value.month.number, index + 1)) },
                modifier = Modifier.weight(0.8f),
            )
            WheelColumn(
                itemCount = yearRange.last - yearRange.first + 1,
                selectedIndex = value.year - yearRange.first,
                label = { index -> (yearRange.first + index).toString() },
                onSelected = { index -> onValueChange(value.withYearClamped(yearRange.first + index)) },
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .offset(y = -WheelRowHeight / 2),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .offset(y = WheelRowHeight / 2),
        )
    }
}

@Composable
private fun WheelColumn(
    itemCount: Int,
    selectedIndex: Int,
    label: (Int) -> String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeSelected = selectedIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeSelected)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val halfRowPx = with(LocalDensity.current) { WheelRowHeight.toPx() / 2f }
    // With one row of vertical content padding on a three-row viewport, the centered
    // item is the first visible one, rounded by half-row scroll offset.
    val centeredIndex by remember(listState, itemCount) {
        derivedStateOf {
            val offsetRows = if (listState.firstVisibleItemScrollOffset > halfRowPx) 1 else 0
            (listState.firstVisibleItemIndex + offsetRows).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(centeredIndex) {
        onSelected(centeredIndex)
    }
    // Reposition only on external corrections (e.g. the day count shrinking when the
    // month changes), never against an in-flight user scroll.
    LaunchedEffect(safeSelected, itemCount) {
        if (!listState.isScrollInProgress && centeredIndex != safeSelected) {
            listState.scrollToItem(safeSelected)
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = WheelRowHeight),
        modifier = modifier.height(WheelRowHeight * 3),
    ) {
        items(count = itemCount) { index ->
            val isCentered = index == centeredIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(WheelRowHeight),
            ) {
                Text(
                    text = label(index),
                    color =
                        if (isCentered) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCentered) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private val WheelRowHeight = 44.dp

private val monthNames =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

internal fun daysInMonth(
    year: Int,
    month: Int,
): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (isLeapYear(year)) 29 else 28
    }

internal fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

internal fun LocalDate.withMonthClamped(month: Int): LocalDate = LocalDate(year, month, day.coerceAtMost(daysInMonth(year, month)))

internal fun LocalDate.withYearClamped(newYear: Int): LocalDate = LocalDate(newYear, month.number, day.coerceAtMost(daysInMonth(newYear, month.number)))
