package com.pynapli.app.profilehub

import com.pynapli.shared.network.dto.AppealDto
import kotlin.test.Test
import kotlin.test.assertEquals

class AppealLabelsTest {
    private fun appeal(
        actionType: String = "risk_restriction",
        status: String,
        adminNote: String? = null,
    ) = AppealDto(
        appealId = "a",
        actionType = actionType,
        targetType = "account",
        status = status,
        userReason = "reason",
        adminNote = adminNote,
        createdAt = "",
        updatedAt = "",
    )

    @Test
    fun appealsReadAsPlainOutcomes() {
        assertEquals("Account restriction", appeal(status = "open").actionLabel())
        assertEquals("In review", appeal(status = "open").statusLabel())
        assertEquals("In review", appeal(status = "escalated").statusLabel())
        assertEquals("Restored: the decision was reversed", appeal(status = "restored").statusLabel())
        assertEquals("Upheld: the decision stands", appeal(status = "upheld").statusLabel())
        assertEquals("Upheld: Repeated spam reports", appeal(status = "upheld", adminNote = "Repeated spam reports").statusLabel())
        assertEquals("Removed photo", appeal(actionType = "media_removal", status = "open").actionLabel())
    }
}
