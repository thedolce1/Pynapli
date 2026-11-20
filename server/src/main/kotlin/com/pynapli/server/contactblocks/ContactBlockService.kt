package com.pynapli.server.contactblocks

import com.pynapli.server.auth.EmailAddressRules
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.SafetyException
import com.pynapli.shared.network.dto.ContactBlockSummaryDto
import java.time.Clock
import java.util.UUID

/**
 * The blocker's side of "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact blocks").
 *
 * The acceptance rule: nothing the blocker can observe depends on whether an address belongs to a
 * member. So every request here does the same work for any valid address: the rate limit, the strict
 * syntax check, one HMAC, one statement on the blocker's own rows (never `users`), and for an add one
 * non-blocking nudge to the off-request resolver. Answers carry no body (204) except the count, which
 * counts every address added, members or not. Errors are about the request only: a malformed address
 * (`INVALID_EMAIL`), the blocker's own cap (`CONTACT_BLOCK_LIMIT_REACHED`) or the daily add budget
 * (`RATE_LIMITED`).
 */
class ContactBlockService(
    private val repository: ContactBlockRepository,
    private val tokens: ContactBlockTokens,
    private val resolutionTrigger: ContactBlockResolutionTrigger,
    private val rateLimitService: RateLimitService? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val idFactory: () -> UUID = UUID::randomUUID,
) {
    suspend fun summary(blockerUserId: UUID): ContactBlockSummaryDto = ContactBlockSummaryDto(count = repository.count(blockerUserId), maxAddresses = MAX_ADDRESSES)

    /** Same 204 for a new address, one already added, a member's or nobody's. */
    suspend fun add(
        blockerUserId: UUID,
        email: String,
    ) {
        // The daily budget counts every attempt, before anything about the address is looked at.
        if (rateLimitService?.check(blockerUserId, RateLimitedAction.CONTACT_BLOCK_ADD)?.allowed == false) {
            throw SafetyException("RATE_LIMITED")
        }
        val emailToken = tokenFor(email)
        val outcome =
            repository.add(
                id = idFactory(),
                blockerUserId = blockerUserId,
                emailToken = emailToken,
                createdAt = clock.instant(),
                maxRows = MAX_ADDRESSES,
            )
        if (outcome == ContactBlockAddOutcome.LIMIT_REACHED) throw SafetyException("CONTACT_BLOCK_LIMIT_REACHED")
        // Never the resolution itself: the worker runs it later, after a random delay.
        resolutionTrigger.requestResolution()
    }

    /** Same 204 whether or not the address was ever added. */
    suspend fun remove(
        blockerUserId: UUID,
        email: String,
    ) {
        repository.remove(blockerUserId, tokenFor(email))
    }

    suspend fun removeAll(blockerUserId: UUID) {
        repository.removeAll(blockerUserId)
    }

    private fun tokenFor(email: String): String {
        val normalised = normalizeEmail(email)
        if (!EmailAddressRules.isStrict(normalised)) throw SafetyException("INVALID_EMAIL")
        return tokens.emailToken(normalised)
    }

    companion object {
        const val MAX_ADDRESSES = 1_000
        const val MAX_ADDS_PER_DAY = 100
    }
}
