package com.pynapli.server.moderation

internal const val ADMIN_RISK_SOURCE_REVIEW = "admin_review"
internal const val ADMIN_RISK_SOURCE_ENFORCEMENT = "admin_enforcement"

internal fun String.adminRiskDefaultScore(): Int =
    when (this) {
        "terminate" -> 90
        "quarantine" -> 70
        "friction" -> 50
        "watch" -> 30
        else -> 0
    }

internal fun String.jsonEscapedForAdminMetadata(): String =
    buildString(length) {
        this@jsonEscapedForAdminMetadata.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
