package com.pynapli.app.onboarding

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DobWheelMathTest {
    @Test
    fun daysInMonthHandlesLeapYears() {
        assertEquals(29, daysInMonth(2024, 2))
        assertEquals(28, daysInMonth(2023, 2))
        // Century rule: 1900 is not a leap year, 2000 is.
        assertEquals(28, daysInMonth(1900, 2))
        assertEquals(29, daysInMonth(2000, 2))
        assertEquals(31, daysInMonth(2024, 1))
        assertEquals(30, daysInMonth(2024, 4))
    }

    @Test
    fun monthChangeClampsTheDay() {
        assertEquals(
            LocalDate(2024, 2, 29),
            LocalDate(2024, 1, 31).withMonthClamped(2),
        )
        assertEquals(
            LocalDate(2023, 2, 28),
            LocalDate(2023, 1, 31).withMonthClamped(2),
        )
        assertEquals(
            LocalDate(2024, 4, 30),
            LocalDate(2024, 3, 31).withMonthClamped(4),
        )
    }

    @Test
    fun yearChangeClampsLeapDay() {
        assertEquals(
            LocalDate(2023, 2, 28),
            LocalDate(2024, 2, 29).withYearClamped(2023),
        )
        assertEquals(
            LocalDate(2000, 2, 29),
            LocalDate(2004, 2, 29).withYearClamped(2000),
        )
    }
}
