package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The uploader sends the server's signed headers verbatim, including the create-only precondition. */
class KtorMediaByteUploaderTest {
    private val createOnly =
        mapOf("Content-Type" to "image/jpeg", "x-goog-if-generation-match" to "0")

    @Test
    fun sendsTheCreateOnlyPreconditionTheServerSigned() =
        runTest {
            var seenPrecondition: String? = null
            var seenMethod: HttpMethod? = null
            val uploader =
                uploader(
                    MockEngine { request ->
                        seenPrecondition = request.headers["x-goog-if-generation-match"]
                        seenMethod = request.method
                        respond("", HttpStatusCode.OK)
                    },
                )

            val result = uploader.upload("https://storage.googleapis.com/b/media/a?sig", createOnly, byteArrayOf(1, 2, 3))

            assertIs<AppResult.Success<Unit>>(result)
            assertEquals("0", seenPrecondition)
            assertEquals(HttpMethod.Put, seenMethod)
        }

    @Test
    fun aCreateOnly412MeansAnEarlierAttemptAlreadyStoredTheObject() =
        runTest {
            val uploader = uploader(MockEngine { respond("", HttpStatusCode.PreconditionFailed) })

            assertIs<AppResult.Success<Unit>>(uploader.upload("https://storage.googleapis.com/b/media/a?sig", createOnly, byteArrayOf(1)))
        }

    @Test
    fun a412WithoutThePreconditionIsStillAFailure() =
        runTest {
            val uploader = uploader(MockEngine { respond("", HttpStatusCode.PreconditionFailed) })

            val result = uploader.upload("https://upload.test/x", mapOf("Content-Type" to "image/jpeg"), byteArrayOf(1))

            assertIs<AppResult.Failure>(result)
        }

    private fun uploader(engine: MockEngine) = KtorMediaByteUploader(HttpClient(engine) { install(HttpTimeout) })
}
