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
import java.util.UUID
import kotlin.random.Random

/**
 * Binds contact blocks to the members they point at (TRUST_AND_SAFETY_SPEC, "Contact blocks").
 * Never on the blocker's request path: the blocker's add only nudges [ContactBlockResolverWorker],
 * which runs a batch after a random delay, so nothing about it lines up with the blocker's requests.
 *
 * Binding a row to member X: when the pair has a recorded interaction ([ContactBlockInteractionSql])
 * the row gets X's exempt token (decided once, kept even if the interaction later expires, so the
 * pair is never bound later and nothing they see changes); otherwise X's target token, after which X
 * never sees the blocker. A blocker who adds their own address gets the exempt token too.
 */
class ContactBlockResolver(
    private val repository: ContactBlockResolverRepository,
    private val tokens: ContactBlockTokens,
) {
    /** One batch of pending rows that now match a verified member. Returns how many rows it settled. */
    suspend fun resolvePendingBatch(batchSize: Int = DEFAULT_BATCH_SIZE): Int {
        val matches = repository.findPendingMatches(batchSize)
        var settled = 0
        for (match in matches) {
            if (settle(match)) settled++
        }
        return settled
    }

    /**
     * A member just proved [normalisedEmail] (sign-up verify): store their contact token and bind every
     * pending row of the address to them, before the verify response (and so before any session or
     * discovery request of theirs) exists. A brand-new account has no interactions, so these all bind.
     */
    suspend fun bindNewlyVerifiedAccount(
        userId: UUID,
        normalisedEmail: String,
    ) {
        val emailToken = tokens.emailToken(normalisedEmail)
        if (!repository.recordContactToken(userId, emailToken, onlyIfUnset = false)) return
        settleAddress(emailToken, userId)
    }

    /**
     * After a successful sign-in with the typed (so the owner's) address: an account verified before
     * contact tokens existed gets its token now, and the address's pending rows are settled before the
     * sign-in answers. Accounts that already have a token, and unverified accounts, change nothing.
     */
    suspend fun recordAddressAtSignIn(
        userId: UUID,
        normalisedEmail: String,
    ) {
        val emailToken = tokens.emailToken(normalisedEmail)
        if (!repository.recordContactToken(userId, emailToken, onlyIfUnset = true)) return
        settleAddress(emailToken, userId)
    }

    private suspend fun settleAddress(
        emailToken: String,
        userId: UUID,
    ) {
        repository.findPendingForAddress(emailToken, userId).forEach { settle(it) }
    }

    private suspend fun settle(match: PendingContactBlockMatch): Boolean {
        val exempt =
            match.blockerUserId == match.targetUserId ||
                repository.hadInteraction(match.blockerUserId, match.targetUserId, match.createdAt)
        val token = if (exempt) tokens.exemptToken(match.targetUserId) else tokens.targetToken(match.targetUserId)
        return repository.bind(match.rowId, match.targetUserId, token)
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 200
    }
}

/** What the blocker's add calls: a non-blocking nudge, never the resolution itself. */
fun interface ContactBlockResolutionTrigger {
    fun requestResolution()
}

/**
 * Runs [ContactBlockResolver.resolvePendingBatch] every [intervalMillis], and soon after a nudge, always
 * after a random [minJitterMillis]..[maxJitterMillis] delay and in batches, on its own background scope.
 * A nudge that arrives while one is pending is merged into it. One replica per tick ([jobLock]).
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
    private var job: kotlinx.coroutines.Job? = null

    override fun requestResolution() {
        nudges.trySend(Unit)
    }

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    withTimeoutOrNull(intervalMillis) { nudges.receive() }
                    delay(minJitterMillis + random.nextLong(0, (maxJitterMillis - minJitterMillis).coerceAtLeast(1)))
                    try {
                        jobLock.runExclusive(JOB_NAME) {
                            var batches = 0
                            while (batches < MAX_BATCHES_PER_TICK &&
                                resolver.resolvePendingBatch() >= ContactBlockResolver.DEFAULT_BATCH_SIZE
                            ) {
                                batches++
                            }
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
        private const val MAX_BATCHES_PER_TICK = 20
    }
}
