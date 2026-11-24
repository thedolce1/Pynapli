package com.pynapli.server.contactblocks

import com.pynapli.server.db.JobLock
import com.pynapli.server.db.UnlockedJobs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Binds contact blocks to the members they point at (TRUST_AND_SAFETY_SPEC, "Contact Blocks").
 * Never on the blocker's request path: the blocker's add only nudges [ContactBlockResolverWorker],
 * which runs a batch after a random delay, so nothing about it lines up with the blocker's requests.
 *
 * Who binds when:
 *  - a member who proves their address (sign-up verify) or signs in with it gets their contact token
 *    and every pending row of the address is settled right there, before the session is returned
 *    ([ContactBlockSettling] makes a failure there harmless and retried);
 *  - a row added for an address whose member already has a token is settled by [resolveRecent]
 *    (every tick looks at rows from the last [RECENT_WINDOW], so a row racing a verify is still seen);
 *  - [resolveAllPending] is the slow safety net, at most once a day across all replicas.
 *
 * Settling a row for member X ([ContactBlockResolverRepository.settle], one transaction under the
 * Swipe pair lock): exempt when X did something the blocker could already see
 * ([ContactBlockInteractionSql]); otherwise bound, after which X never sees the blocker. A blocker
 * who adds their own address gets the exempt token.
 */
class ContactBlockResolver(
    private val repository: ContactBlockResolverRepository,
    private val tokens: ContactBlockTokens,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Rows added in the last [RECENT_WINDOW]. Returns how many it settled. */
    suspend fun resolveRecent(): Int = resolveSince(clock.instant().minus(RECENT_WINDOW))

    /** Every pending row, in batches. Returns how many it settled. */
    suspend fun resolveAllPending(): Int = resolveSince(Instant.EPOCH)

    /** The daily full sweep, when no replica has run it in the last [FULL_SWEEP_EVERY]; else the recent pass. */
    suspend fun resolveScheduled(): Int {
        if (!repository.claimFullSweep(clock.instant(), FULL_SWEEP_EVERY)) return resolveRecent()
        // Success is recorded only after the sweep finished: a failed one is retried within the hour.
        val settled = resolveAllPending()
        repository.recordFullSweepSuccess(clock.instant())
        return settled
    }

    private suspend fun resolveSince(createdSince: Instant): Int {
        var settled = 0
        var after: PendingContactBlockRow? = null
        while (true) {
            val rows = repository.listPendingRows(createdSince, after, BATCH_SIZE)
            if (rows.isEmpty()) break
            val memberTokens = rows.associateWith { tokens.memberToken(it.emailToken) }
            val members = repository.findVerifiedMembers(memberTokens.values)
            for (row in rows) {
                val memberToken = memberTokens.getValue(row)
                val memberId = members[memberToken] ?: continue
                if (settle(row, memberId, memberToken) != ContactBlockSettleOutcome.UNCHANGED) settled++
            }
            if (rows.size < BATCH_SIZE) break
            after = rows.last()
        }
        return settled
    }

    /**
     * A member just proved [normalisedEmail] (sign-up verify): store their contact token and settle
     * every pending row of the address, before the verify response (and so before any session or
     * discovery request of theirs) exists. A brand-new account has no interactions, so these bind.
     */
    suspend fun bindNewlyVerifiedAccount(
        userId: UUID,
        normalisedEmail: String,
    ) {
        settleAddress(userId, tokens.emailToken(normalisedEmail))
    }

    /**
     * After every successful sign-in with the typed (so the owner's) address: an account verified
     * before contact tokens existed gets its token now, and pending rows of the address are settled
     * before the sign-in answers. Unverified accounts are never given a token and never bound.
     */
    suspend fun recordAddressAtSignIn(
        userId: UUID,
        normalisedEmail: String,
    ) {
        settleAddress(userId, tokens.emailToken(normalisedEmail))
    }

    /** The same settling for an address known only by its email token (the worker's retries). */
    suspend fun settleAddress(
        userId: UUID,
        emailToken: String,
    ) {
        val memberToken = tokens.memberToken(emailToken)
        repository.recordContactToken(userId, memberToken)
        repository.listPendingForAddress(emailToken).forEach { row -> settle(row, userId, memberToken) }
    }

    fun emailTokenOf(normalisedEmail: String): String = tokens.emailToken(normalisedEmail)

    private suspend fun settle(
        row: PendingContactBlockRow,
        targetUserId: UUID,
        memberToken: String,
    ): ContactBlockSettleOutcome =
        repository.settle(
            row = row,
            targetUserId = targetUserId,
            memberToken = memberToken,
            targetToken = tokens.targetToken(targetUserId),
            exemptToken = tokens.exemptToken(targetUserId),
        )

    companion object {
        const val BATCH_SIZE = 500

        /** Rows younger than this are looked at on every tick. */
        val RECENT_WINDOW: Duration = Duration.ofMinutes(30)

        /** At most one full pass over every pending row this often, across all replicas. */
        val FULL_SWEEP_EVERY: Duration = Duration.ofDays(1)
    }
}

/** What the blocker's add calls: a non-blocking nudge, never the resolution itself. */
fun interface ContactBlockResolutionTrigger {
    fun requestResolution()
}

/**
 * The verify-time and sign-in-time settling, made harmless to fail. A lock wait or an SQL error there
 * can only happen when someone added the member's address, so it must never fail the member's sign-in
 * (a signal and a lockout) or their verify (the account already exists and the code is spent). On
 * failure it logs the exception class only, queues the address on the worker for its next tick (a
 * few seconds), and returns normally.
 *
 * The window when it fails: until that tick (5 to 30 seconds), the member's reads don't yet hide the
 * rows of that address that weren't settled. The retry queue is in memory on THIS instance only: a
 * restart in that window loses the retry and other replicas never see it; the member's next sign-in
 * settles again, and the daily full sweep covers the address's pending rows (its member has a token).
 * The settle runs with a 2 s lock timeout, so a held lock lands here fast instead of stalling.
 */
class ContactBlockSettling(
    private val resolver: ContactBlockResolver,
    private val worker: ContactBlockResolverWorker?,
) {
    suspend fun atVerify(
        userId: UUID,
        normalisedEmail: String,
    ) = guarded(userId, normalisedEmail) { resolver.bindNewlyVerifiedAccount(userId, normalisedEmail) }

    suspend fun atSignIn(
        userId: UUID,
        normalisedEmail: String,
    ) = guarded(userId, normalisedEmail) { resolver.recordAddressAtSignIn(userId, normalisedEmail) }

    private suspend fun guarded(
        userId: UUID,
        normalisedEmail: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Contact block settling deferred ({})", error.javaClass.simpleName)
            worker?.retrySettle(userId, resolver.emailTokenOf(normalisedEmail))
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ContactBlockSettling::class.java)
    }
}

/**
 * Runs [ContactBlockResolver.resolveScheduled] (the recent pass, and the daily full sweep when it is
 * due) every [intervalMillis] and soon after a nudge, always after a random
 * [minJitterMillis]..[maxJitterMillis] delay, on its own background scope; and retries addresses
 * whose verify/sign-in settling failed. Nudges arriving while one is pending are merged. One replica
 * per tick ([jobLock]).
 */
class ContactBlockResolverWorker(
    private val resolver: ContactBlockResolver,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val minJitterMillis: Long = DEFAULT_MIN_JITTER_MILLIS,
    private val maxJitterMillis: Long = DEFAULT_MAX_JITTER_MILLIS,
    private val jobLock: JobLock = UnlockedJobs,
    private val random: Random = Random.Default,
) : ContactBlockResolutionTrigger,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(ContactBlockResolverWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private val retries = ConcurrentLinkedQueue<Pair<UUID, String>>()
    private var job: kotlinx.coroutines.Job? = null

    override fun requestResolution() {
        nudges.trySend(Unit)
    }

    /** Queues one address (by email token) of [userId] to settle on the next tick. */
    fun retrySettle(
        userId: UUID,
        emailToken: String,
    ) {
        if (retries.size < MAX_QUEUED_RETRIES) retries.add(userId to emailToken)
        nudges.trySend(Unit)
    }

    /** One tick's work, without the wait (tests call this directly). */
    suspend fun runOnce() {
        jobLock.runExclusive(JOB_NAME) {
            while (true) {
                val (userId, emailToken) = retries.poll() ?: break
                resolver.settleAddress(userId, emailToken)
            }
            resolver.resolveScheduled()
        }
    }

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    withTimeoutOrNull(intervalMillis) { nudges.receive() }
                    delay(minJitterMillis + random.nextLong(0, (maxJitterMillis - minJitterMillis).coerceAtLeast(1)))
                    try {
                        runOnce()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Error class only: nothing about who or which rows.
                        logger.warn("Contact block resolution failed ({})", error.javaClass.simpleName)
                    }
                }
            }
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        const val JOB_NAME = "contact-block-resolver"
        const val DEFAULT_INTERVAL_MILLIS = 60_000L
        const val DEFAULT_MIN_JITTER_MILLIS = 5_000L
        const val DEFAULT_MAX_JITTER_MILLIS = 30_000L
        private const val MAX_QUEUED_RETRIES = 10_000
    }
}
