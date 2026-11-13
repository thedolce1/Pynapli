package com.pynapli.app.grid

import com.pynapli.shared.network.PynapliJson
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisitingSoonRequestTest {
    @Test
    fun theVisitingSoonFilterIsSentToTheServer() {
        val json = PynapliJson.encodeToString(GridQueryRequestDto.serializer(), GridFilters(visitingSoon = true).toGridQueryRequest(null))
        assertTrue(json.contains("\"visitingSoonOnly\":true"), json)
    }

    @Test
    fun aVisitingSoonCardWithNoDistanceDecodesAndShowsItsDates() {
        // The server leaves nulls out (explicitNulls = false): no distanceLabel key at all.
        val body =
            """{"items":[{"userId":"u1","displayName":"Maya","online":false,"rankToken":"grid:v1:u1","visitingFrom":"2026-11-16","visitingUntil":"2026-11-18"}]}"""
        val card =
            PynapliJson
                .decodeFromString(GridQueryResponseDto.serializer(), body)
                .items
                .single()
                .toGridProfileCardUi(0)
        assertEquals("16–18 Nov", card.visitingDates)
        assertEquals(null, card.distanceLabel)
    }
}
