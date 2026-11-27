package com.pynapli.server.auth.social

import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.dependencies.RedisCache
import io.lettuce.core.SetArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-issued, single-use nonces for provider ID tokens. Only a hash of each nonce is stored, so a
 * store dump can't be replayed. [consume] is atomic: of two requests presenting the same nonce, at
 * most one gets true.
 */
interface SocialNonceStore {
    /** Remembers [nonce] for [lifetime]. */
    suspend fun remember(
        nonce: String,
        lifetime: Duration,
    )

    /** True exactly once for a remembered, unexpired nonce; it is gone afterwards. */
    suspend fun consume(nonce: String): Boolean
}

/** Production: one Redis key per nonce (SET NX EX, then GETDEL), shared by every replica. */
class RedisSocialNonceStore(
    private val redisCache: RedisCache,
) : SocialNonceStore {
    override suspend fun remember(
        nonce: String,
        lifetime: Duration,
    ) {
        withContext(Dispatchers.IO) {
            redisCache.commands.set(keyFor(nonce), "1", SetArgs.Builder.nx().ex(lifetime.seconds))
        }
    }

    override suspend fun consume(nonce: String): Boolean = withContext(Dispatchers.IO) { redisCache.commands.getdel(keyFor(nonce)) != null }

    private fun keyFor(nonce: String): String = "pynapli:social-nonce:${Sha256.hash(nonce)}"
}

/** Tests and single-process fakes only. */
class InMemorySocialNonceStore(
    private val clock: Clock = Clock.systemUTC(),
) : SocialNonceStore {
    private val nonces = ConcurrentHashMap<String, Instant>()

    override suspend fun remember(
        nonce: String,
        lifetime: Duration,
    ) {
        nonces.putIfAbsent(Sha256.hash(nonce), clock.instant().plus(lifetime))
    }

    override suspend fun consume(nonce: String): Boolean {
        val expiresAt = nonces.remove(Sha256.hash(nonce)) ?: return false
        return clock.instant().isBefore(expiresAt)
    }

    val size: Int get() = nonces.size
}
