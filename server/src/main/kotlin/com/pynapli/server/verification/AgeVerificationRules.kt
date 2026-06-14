package com.pynapli.server.verification

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.util.UUID

internal const val AGE_DEFAULT_MARKET = "default"
internal const val AGE_METHOD_MANUAL_REVIEW = "manual_review"
internal const val AGE_METHOD_PROVIDER_CALLBACK = "provider_callback"
internal const val AGE_PROVIDER_INTERNAL_MANUAL = "internal_manual"
internal const val AGE_PROVIDER_CALLBACK = "provider"
internal const val AGE_STATUS_NOT_STARTED = "not_started"
internal const val AGE_STATUS_PENDING = "pending"
internal const val AGE_STATUS_VERIFIED = "verified"
internal const val AGE_STATUS_REJECTED = "rejected"
private val SAFE_TOKEN_REGEX = Regex("""[a-z0-9_-]{2,40}""")

// A market flag is only honored while a provider is configured: with none,
// manual review stays available so a policy misconfiguration cannot lock
// users out of verification entirely.
internal fun enhancedRequiredFor(
    provider: AgeVerificationProvider?,
    marketPolicy: AgeVerificationMarketPolicy,
    market: String,
): Boolean = provider != null && marketPolicy.enhancedRequired(market)

internal fun isAdult(
    birthdate: LocalDate,
    clock: Clock,
): Boolean = Period.between(birthdate, LocalDate.now(clock)).years >= 18

internal fun String?.cleanMarket(): String = this?.trim()?.lowercase()?.takeIf { it.matches(SAFE_TOKEN_REGEX) } ?: AGE_DEFAULT_MARKET

internal fun String?.cleanProvider(): String = this?.trim()?.lowercase()?.takeIf { it.matches(SAFE_TOKEN_REGEX) } ?: AGE_PROVIDER_CALLBACK

internal fun String?.cleanReason(): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.length > 500) throw VerificationException("VERIFICATION_REASON_TOO_LONG")
    return value
}

internal fun String.normalizeReviewStatus(): String =
    when (trim().lowercase()) {
        "approved", AGE_STATUS_VERIFIED -> AGE_STATUS_VERIFIED
        AGE_STATUS_REJECTED -> AGE_STATUS_REJECTED
        else -> throw VerificationException("INVALID_VERIFICATION_STATUS")
    }

internal fun String.toUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw VerificationException("INVALID_VERIFICATION_ID") }

internal fun String.toInstantOrThrow(): Instant = runCatching { Instant.parse(this) }.getOrElse { throw VerificationException("INVALID_VERIFICATION_CALLBACK") }
