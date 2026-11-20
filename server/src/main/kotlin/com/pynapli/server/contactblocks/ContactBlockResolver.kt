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
import kotlin.random.Random

/**
 * Binds contact blocks to the members they point at (TRUST_AND_SAFETY_SPEC, "Contact blocks").
 * Never on the blocker's request path: the blocker's add only nudges [ContactBlockResolverWorker],
 * which runs a batch after a random delay, so nothing about it lines up with the blocker's requests.
 *
 * Who binds when:
 *  - a member who proves their address (sign-up verify) or signs in with it gets their contact token
 *    and every pending row of the address is settled right there, before the session is returned;
 *  - a row added for an address whose member already has a token is settled by [resolveRecent]
 *    (every tick looks at rows from the last [RECENT_WINDOW], so a row racing a verify is still seen);
 *  - [resolveAllPending] is the slow safety net (rows missed while the server was down).
 *
 * Settling a row for member X: when the pair has a recorded interaction ([ContactBlockInteractionSql])
 * the row gets X's exempt token (decided once and kept, so the pair is never bound later and nothing
 * either of them sees changes); otherwise X's target token, after which X never sees the blocker. A
 * blocker who adds their own address gets the exempt token too.
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
                if (settle(row, memberId, memberToken)) settled++
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
     * before the sign-in answers (again each time, so a verify whose settling failed is caught up the
     * next time the member signs in). Unverified accounts are never given a token and never bound.
     */
    suspend fun recordAddressAtSignIn(
        userId: UUID,
        normalisedEmail: String,
    ) {
        settleAddress(userId, tokens.emailToken(normalisedEmail))
    }

    private suspend fun settleAddress(
        userId: UUID,
        emailToken: String,
    ) {
        val memberToken = tokens.memberToken(emailToken)
        repository.recordContactToken(userId, memberToken)
        repository.listPendingForAddress(emailToken).forEach { row -> settle(row, userId, memberToken) }
    }

    private suspend fun settle(
        row: PendingContactBlockRow,
        targetUserId: UUID,
        memberToken: String,
    ): Boolean {
        val exempt =
            row.blockerUserId == targetUserId ||
                repository.hadInteraction(row.blockerUserId, targetUserId, row.createdAt)
        val token = if (exempt) tokens.exemptToken(targetUserId) else tokens.targetToken(targetUserId)
        // Refused (nothing changes) unless targetUserId still holds memberToken: an unverified or
        // deleted account is never bound.
        return repository.bind(row.rowId, targetUserId, memberToken, token)
    }

    companion object {
        const val BATCH_SIZE = 500

        /** Rows younger than this are looked at on every tick. */
        val RECENT_WINDOW: Duration = Duration.ofMinutes(30)
    }
}

/** What the blocker's add calls: a non-blocking nudge, never the resolution itself. */
fun interface ContactBlockResolutionTrigger {
    fun requestResolution()
}

/**
 * Runs [ContactBlockResolver.resolveRecent] every [intervalMillis] and soon after a nudge, always after
 * a random [minJitterMillis]..[maxJitterMillis] delay, on its own background scope; and
 * [ContactBlockResolver.resolveAllPending] every [fullSweepEvery] ticks. Nudges arriving while one is
 * pending are merged. One replica per tick ([jobLock]).
 */
class ContactBlockResolverWorker(
    private val resolver: ContactBlockResolver,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val minJitterMillis: Long = DEFAULT_MIN_JITTER_MILLIS,
    private val maxJitterMillis: Long = DEFAULT_MAX_JITTER_MILLIS,
    private val fullSweepEvery: Int = DEFAULT_FULL_SWEEP_EVERY,
    private val jobLock: JobLock = UnlockedJobs,
    private val random: Random = Random.Default,
) : ContactBlockResolutionTrigger,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(ContactBlockResolverWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private var job: kotlinx.coroutines.Job? = null

    override fun requestResolution() {
        nudges.trySend(Unit)
    }

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                var tick = 0
                while (isActive) {
                    withTimeoutOrNull(intervalMillis) { nudges.receive() }
                    delay(minJitterMillis + random.nextLong(0, (maxJitterMillis - minJitterMillis).coerceAtLeast(1)))
                    tick++
                    try {
                        jobLock.runExclusive(JOB_NAME) {
                            // The first tick of every instance sweeps everything: with scale-to-zero an
                            // instance may never live long enough to reach the periodic full sweep.
                            if (tick == 1 || tick % fullSweepEvery == 0) resolver.resolveAllPending() else resolver.resolveRecent()
                        }
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

        /** About every 6 hours at the default interval. */
        const val DEFAULT_FULL_SWEEP_EVERY = 360
    }
}
