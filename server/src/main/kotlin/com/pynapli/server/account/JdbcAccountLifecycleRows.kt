package com.pynapli.server.account

import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.toAccountDataExportRecord(): AccountDataExportRecord =
    AccountDataExportRecord(
        id = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        status = getString("status"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
        expiresAt = getTimestamp("expires_at")?.toInstant(),
        downloadUrl = getString("download_url"),
        errorMessage = getString("error_message"),
    )
