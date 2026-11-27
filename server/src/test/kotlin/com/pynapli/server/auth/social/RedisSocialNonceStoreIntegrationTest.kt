package com.pynapli.server.auth.social

import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.testsupport.PynapliTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The production nonce store against the shared Redis test container (SET NX EX, then GETDEL). */
class RedisSocialNonceStoreIntegrationTest {
    @Test
    fun aNonceIsStoredOnlyAsAHashWithATtlAndSpentExactlyOnce() =
        runBlocking {
            PynapliTestDatabase.redisCache().use { redis ->
                val store = RedisSocialNonceStore(redis)
                store.remember("nonce-a", Duration.ofMinutes(10))

                val key = "pynapli:social-nonce:${Sha256.hash("nonce-a")}"
                assertEquals("1", redis.commands.get(key))
                assertTrue(redis.commands.ttl(key) in 1L..600L, "expires with the nonce")
                assertTrue(redis.commands.keys("*nonce-a*").isEmpty(), "the raw nonce is never a key")

                // Remembering again never extends or resets an issued nonce (NX).
                redis.commands.expire(key, 5)
                store.remember("nonce-a", Duration.ofMinutes(10))
                assertTrue(redis.commands.ttl(key) <= 5)

                assertTrue(store.consume("nonce-a"))
                assertNull(redis.commands.get(key))
                assertFalse(store.consume("nonce-a"))
                assertFalse(store.consume("never-issued"))
            }
        }

    @Test
    fun concurrentConsumersOfOneNonceGetExactlyOneSuccess() =
        runBlocking {
            PynapliTestDatabase.redisCache().use { redis ->
                val store = RedisSocialNonceStore(redis)
                store.remember("nonce-race", Duration.ofMinutes(10))
                val wins =
                    (1..16)
                        .map { async(Dispatchers.IO) { store.consume("nonce-race") } }
                        .awaitAll()
                        .count { it }
                assertEquals(1, wins)
            }
        }
}
