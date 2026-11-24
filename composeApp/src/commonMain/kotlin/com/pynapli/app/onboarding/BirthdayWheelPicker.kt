package com.pynapli.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliShape
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

private val WheelItemHeight = 40.dp

// Odd, so one row sits in the middle: two above and two below the selection.
private const val WHEEL_VISIBLE_ITEMS = 5

private val monthNames =
    listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/**
 * The phone birthday picker (onboarding + first grid plan, Section 1): three scroll wheels, day /
 * month / year in the DD/MM/YYYY order the typed fields use, built on [LazyColumn] with snapping.
 * The screen passes the date the holder's fields hold; every move goes back through
 * [onDateChange], where the holder clamps the day to the month, so the date math lives (and is
 * tested) in [OnboardingStateHolder], not here.
 *
 * Accessibility: each wheel is one semantics node (label, current value as its state, adjustable
 * like a slider, plus explicit next/previous actions), and the step offers "type it instead".
 */
@Composable
internal fun BirthdayWheelPicker(
    day: Int,
    month: Int,
    year: Int,
    years: IntRange,
    onDateChange: (day: Int, month: Int, year: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(year, month) { (1..OnboardingStateHolder.daysInMonth(year, month)).toList() }
    val months = remember { (1..12).toList() }
    val yearValues = remember(years) { years.toList() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        WheelColumn(
            label = "Day",
            values = days,
            selected = day,
            display = { it.toString() },
            onSelected = { onDateChange(it, month, year) },
            modifier = Modifier.weight(1f),
        )
        WheelColumn(
            label = "Month",
            values = months,
            selected = month,
            display = { monthNames[it - 1] },
            onSelected = { onDateChange(day, it, year) },
            modifier = Modifier.weight(1.8f),
        )
        WheelColumn(
            label = "Year",
            values = yearValues,
            selected = year,
            display = { it.toString() },
            onSelected = { onDateChange(day, month, it) },
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun WheelColumn(
    label: String,
    values: List<Int>,
    selected: Int,
    display: (Int) -> String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = values.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val itemHeightPx = with(LocalDensity.current) { WheelItemHeight.toPx() }
    val currentValues by rememberUpdatedState(values)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelected by rememberUpdatedState(onSelected)

    // The row in the middle slot. The content padding (two rows each side) puts row i in the middle
    // when it is the first visible row with no offset.
    val centredIndex by remember(listState) {
        derivedStateOf {
            val pastHalf = if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) 1 else 0
            (listState.firstVisibleItemIndex + pastHalf).coerceIn(0, (currentValues.size - 1).coerceAtLeast(0))
        }
    }

    // A settled scroll is a choice.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { scrolling -> !scrolling }
            .collect {
                val value = currentValues.getOrNull(centredIndex) ?: return@collect
                if (value != currentSelected) currentOnSelected(value)
            }
    }
    // The other way round: the value changed outside the wheel (the day clamped to a shorter month,
    // or an accessibility action), so bring that row to the middle.
    LaunchedEffect(selectedIndex, values.size) {
        if (!listState.isScrollInProgress && centredIndex != selectedIndex) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    fun move(by: Int): Boolean {
        val target = (selectedIndex + by).coerceIn(0, values.lastIndex)
        if (target == selectedIndex) return false
        onSelected(values[target])
        return true
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(WheelItemHeight * WHEEL_VISIBLE_ITEMS)
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = display(selected)
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            current = selectedIndex.toFloat(),
                            range = 0f..values.lastIndex.coerceAtLeast(1).toFloat(),
                            steps = (values.size - 2).coerceAtLeast(0),
                        )
                    setProgress { target ->
                        val index = target.roundToInt().coerceIn(0, values.lastIndex)
                        if (index != selectedIndex) onSelected(values[index])
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Next ${label.lowercase()}") { move(1) },
                            CustomAccessibilityAction("Previous ${label.lowercase()}") { move(-1) },
                        )
                },
    ) {
        // The selection band behind the middle row.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(WheelItemHeight)
                    .clip(PynapliShape.CardShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = WheelItemHeight * (WHEEL_VISIBLE_ITEMS / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(values, key = { _, value -> value }) { index, value ->
                val isCentre = index == centredIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().height(WheelItemHeight),
                ) {
                    Text(
                        text = display(value),
                        color = if (isCentre) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCentre) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
