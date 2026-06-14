package com.pynapli.server.safety

import java.sql.Connection

internal const val APPEAL_UNIQUE_VIOLATION_SQLSTATE = "23505"

internal fun Connection.exists(
    sql: String,
    vararg values: Any,
): Boolean =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value ->
            statement.setObject(index + 1, value)
        }
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }
